package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.agent.IndexingAgent;
import com.procuremind.ai_service.dto.NodeSummary;
import com.procuremind.ai_service.entity.PageIndexNode;
import com.procuremind.ai_service.service.kafka.ContractEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Summarises each parsed {@link PageIndexNode} with the LLM.
 *
 * <p>This runs on the Kafka consumer thread, so it is bounded on purpose:
 * <ul>
 *   <li><b>Incremental durability</b> — every node is committed as soon as it is
 *       summarised, in its own transaction. A crash, a timeout or a Kafka redelivery
 *       therefore <em>resumes</em> rather than restarting: the driving query only selects
 *       nodes whose summary is still null.</li>
 *   <li><b>A wall-clock budget</b> ({@code app.indexing.max-duration}, default 20m, well
 *       under the consumer's 30m {@code max.poll.interval.ms}) — when it is exhausted the
 *       method throws, the record is retried by the container's error handler, and the
 *       retry picks up exactly where this attempt stopped. The consumer is never blocked
 *       past the point where the broker would evict it from the group.</li>
 *   <li><b>No fixed sleep</b> — the old unconditional {@code Thread.sleep(3000)} per node
 *       is now {@code app.indexing.inter-call-delay}, default {@code 0}. The local Ollama
 *       provider needs no throttling; the knob remains for rate-limited hosted providers.</li>
 * </ul>
 *
 * <p>Ordering and idempotency are unchanged: a single consumer thread processes one
 * contract at a time, and re-running is a no-op once every node has a summary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final PageIndexNodeRepository nodeRepository;
    private final IndexingAgent indexingAgent;
    private final ContractEventProducer eventProducer;

    @Value("${app.indexing.inter-call-delay:0ms}")
    private Duration interCallDelay;

    @Value("${app.indexing.max-duration:20m}")
    private Duration maxDuration;

    /** Upper bound on the text sent to the summarising model for one node. */
    @Value("${app.indexing.max-node-chars:12000}")
    private int maxNodeChars;

    /**
     * Summarise every not-yet-indexed node for the contract, then announce completion.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}: a single transaction spanning
     * dozens of LLM round-trips would hold a database connection for minutes and lose all
     * progress on failure. Each node is persisted in its own short transaction instead.
     *
     * @throws IndexingIncompleteException when the time budget is exhausted or no node
     *         could be summarised at all — both are retryable, and the retry resumes.
     */
    public void indexContractNodes(UUID documentId) {
        List<PageIndexNode> pending = nodeRepository.findByDocumentIdAndSummaryIsNull(documentId);

        if (pending.isEmpty()) {
            log.info("[INDEXING] Contract {}: all nodes already indexed, publishing contract.indexed", documentId);
            eventProducer.publishPageIndexed(documentId);
            return;
        }

        log.info("[INDEXING] Contract {}: starting, {} node(s) pending", documentId, pending.size());

        long deadline = System.nanoTime() + maxDuration.toNanos();
        int total = pending.size();
        int done = 0;
        int failed = 0;

        for (PageIndexNode node : pending) {
            if (System.nanoTime() > deadline) {
                throw new IndexingIncompleteException(
                        "Indexing budget of %s exhausted for contract %s after %d/%d node(s); "
                                .formatted(maxDuration, documentId, done, total)
                                + "the retry will resume from the remaining nodes");
            }

            int position = done + failed + 1;
            try {
                log.info("[INDEXING] Contract {}: processing node {}/{}", documentId, position, total);
                String rawContext = boundedContext(node, documentId, position, total);
                log.info("[INDEXING] Contract {}: node {}/{} extracted successfully ({} characters)",
                        documentId, position, total, rawContext.length());

                log.info("[SUMMARIZING] Contract {}: summarizing node {}/{}", documentId, position, total);
                NodeSummary summary = indexingAgent.summarize(rawContext);
                persistSummary(node, summary);
                done++;
                log.info("[SUMMARIZING] Contract {}: node {}/{} summary completed", documentId, position, total);
            } catch (Exception e) {
                failed++;
                log.error("[SUMMARIZING] Contract {}: node {}/{} FAILED ({}): {}",
                        documentId, position, total, node.getId(), e.toString());
            }

            pause();
        }

        // Every node failed: this is a systemic problem (LLM unreachable, model missing),
        // not a per-node quirk. Fail loudly so the record is retried and then dead-lettered
        // instead of advancing the contract with an empty table of contents.
        if (done == 0) {
            throw new IndexingIncompleteException(
                    "No node could be indexed for contract %s (%d attempted, all failed)".formatted(documentId, total));
        }
        if (failed > 0) {
            log.warn("[INDEXING] Contract {}: {} of {} node(s) indexed, {} still unsummarised and will be retried "
                    + "if this contract is reprocessed", documentId, done, total, failed);
        }

        eventProducer.publishPageIndexed(documentId);
        log.info("[INDEXING] Contract {}: completed {}/{} nodes, contract.indexed published",
                documentId, done, total);
    }

    /**
     * Persist one summarised node immediately.
     *
     * <p>Intentionally has no {@code @Transactional} of its own: this is a self-invocation,
     * so a proxy-based annotation here would be silently bypassed. Spring Data's
     * {@code SimpleJpaRepository.save} is already transactional, so with no surrounding
     * transaction each call commits on its own — which is exactly the per-node durability
     * this loop needs.
     */
    /**
     * Caps the text handed to the summarising model.
     *
     * <p>A document whose headings do not match the parser's patterns collapses into a single
     * ROOT node holding everything. Passing tens of thousands of characters to an 8B local
     * model overflows its context and yields a useless one-line summary, so the head of the
     * section is summarised instead of nothing.
     */
    private String boundedContext(PageIndexNode node, UUID documentId, int position, int total) {
        String rawContext = node.getRawContext() == null ? "" : node.getRawContext();
        if (rawContext.length() <= maxNodeChars) {
            return rawContext;
        }
        log.warn("[INDEXING] Contract {}: node {}/{} is {} characters, above the {} character budget; "
                        + "summarising the first {} characters only",
                documentId, position, total, rawContext.length(), maxNodeChars, maxNodeChars);
        return rawContext.substring(0, maxNodeChars);
    }

    private void persistSummary(PageIndexNode node, NodeSummary summary) {
        node.setTitle(summary.title());
        node.setSummary(summary.summary());
        nodeRepository.save(node);
    }

    private void pause() {
        if (interCallDelay.isZero() || interCallDelay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(interCallDelay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Retryable: the contract is partially indexed and a redelivery will resume it. */
    public static class IndexingIncompleteException extends RuntimeException {
        public IndexingIncompleteException(String message) {
            super(message);
        }
    }
}
