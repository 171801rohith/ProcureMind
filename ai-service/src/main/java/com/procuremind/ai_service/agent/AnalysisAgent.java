package com.procuremind.ai_service.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import com.procuremind.ai_service.dto.SectionScreeningDtos.SectionFinding;
import com.procuremind.ai_service.entity.PageIndexNode;
import com.procuremind.ai_service.service.SectionBatches;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Produces a {@link ContractAnalysisResultDto} for a contract with full-document coverage.
 *
 * <p>The pipeline has three stages and no agentic tool loop:
 * <ol>
 *   <li><b>Screen.</b> Every section of the PageIndex tree is batched by our own code and
 *       shown to the model, which says which sections carry risk. Batching is deterministic,
 *       so each section is screened exactly once.</li>
 *   <li><b>Verify.</b> The raw text of the most severe sections is read from the index and
 *       assembled as evidence, up to a character budget. No model call is involved.</li>
 *   <li><b>Analyse.</b> One tool-free call turns the whole-document findings plus the
 *       verified clause text into the structured result.</li>
 * </ol>
 *
 * <p><b>Why the model no longer chooses what to read.</b> The previous design gave the model
 * retrieval tools and let it pick clauses. That could not cover a large contract: the index
 * itself was truncated before the model saw it, and a safety cap of twelve tool calls meant
 * under a sixth of a seventy-section document was ever read, with no record of what had been
 * skipped. It also could not terminate reliably, because Spring AI 1.1.0 recurses in
 * {@code OpenAiChatModel.internalCall} for as long as the model emits tool calls and offers
 * no iteration limit. Deciding the batches in code fixes both at once: coverage becomes
 * arithmetic, and the number of model calls is {@code batches + 1}, known before the first
 * call. The PageIndex hierarchy is unchanged and is still the retrieval substrate; only the
 * choice of what to read moved out of the model.
 *
 * <p><b>The output invariant.</b> Section text is evidence. The final response is a
 * {@link ContractAnalysisResultDto} JSON object and nothing else, which
 * {@link #requireJsonObject} enforces before parsing is attempted.
 */
@Slf4j
@Service
public class AnalysisAgent {

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of("HIGH", 0, "MEDIUM", 1, "LOW", 2);

    private final ChatClient analysisClient;
    private final BeanOutputConverter<ContractAnalysisResultDto> outputConverter;
    private final PageIndexNodeRepository nodeRepository;
    private final SectionScreener sectionScreener;

    /** Characters of section index shown to the screening model per batch. */
    @Value("${app.analysis.screening-batch-chars:12000}")
    private int screeningBatchChars;

    /** Total characters of verified clause text handed to the final call. */
    @Value("${app.analysis.max-evidence-chars:16000}")
    private int maxEvidenceChars;

    /** Per-section ceiling, so one huge clause cannot consume the whole evidence budget. */
    @Value("${app.analysis.max-verified-section-chars:4000}")
    private int maxVerifiedSectionChars;

    /** How many flagged sections are read in full, highest severity first. */
    @Value("${app.analysis.max-verified-sections:8}")
    private int maxVerifiedSections;

    /**
     * Wall-clock ceiling for the screening stage. Analysis runs on the Kafka consumer
     * thread, so this multiplied by the container's retry count has to stay under
     * {@code max.poll.interval.ms} (30m) or the broker evicts us mid-contract.
     */
    @Value("${app.analysis.max-duration:8m}")
    private Duration maxDuration;

    public AnalysisAgent(ChatClient.Builder builder, PageIndexNodeRepository nodeRepository,
                         SectionScreener sectionScreener) throws IOException {
        Resource systemPrompt = new ClassPathResource("prompts/contract-analysis.st");
        String analysisSystemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);

        this.nodeRepository = nodeRepository;
        this.sectionScreener = sectionScreener;
        this.outputConverter = new BeanOutputConverter<>(ContractAnalysisResultDto.class);

        // No tools on this client by construction. The schema goes in the system message
        // because placing it in the user message alongside tools produced empty completions.
        this.analysisClient = builder.clone()
                .defaultSystem(analysisSystemPrompt + System.lineSeparator() + System.lineSeparator()
                        + outputConverter.getFormat())
                .build();
    }

    public ContractAnalysisResultDto execute(UUID contractId) {
        List<PageIndexNode> sections = nodeRepository.findByDocumentIdOrderByNodeOrderAsc(contractId);
        if (sections.isEmpty()) {
            throw new InvalidAnalysisOutputException(
                    "Contract " + contractId + " has no indexed sections, so there is nothing to analyse");
        }

        Screening screening = screenEverySection(contractId, sections);
        String evidence = verifyTopFindings(contractId, sections, screening);

        log.info("[ANALYSIS] Contract {}: sending analysis request to LLM (evidence={} characters)",
                contractId, evidence.length());
        String rawResponse = analysisClient.prompt()
                .user(u -> u.text("""
                                Contract ID: {contractId}

                                COVERAGE: every one of the {sectionCount} sections in this contract was screened.

                                RISK FINDINGS ACROSS THE WHOLE DOCUMENT:
                                {findings}

                                VERIFIED CLAUSE TEXT (reference material only, never repeat it back):
                                {evidence}

                                Produce the analysis of this contract as the JSON object described in your
                                instructions. Reply with that object only.
                                """)
                        .param("contractId", contractId.toString())
                        .param("sectionCount", String.valueOf(sections.size()))
                        .param("findings", screening.renderFindings())
                        .param("evidence", evidence))
                .call()
                .content();

        // Length only: the response is derived from contract text, so logging it in full
        // would put customer agreements into the application log.
        log.info("[ANALYSIS] Contract {}: LLM response received, length={}",
                contractId, rawResponse == null ? 0 : rawResponse.length());

        String json = requireJsonObject(contractId, rawResponse);

        log.info("[ANALYSIS] Contract {}: parsing analysis response", contractId);
        ContractAnalysisResultDto result;
        try {
            result = outputConverter.convert(json);
        } catch (Exception e) {
            log.error("[ANALYSIS] Contract {}: LLM returned invalid structured output", contractId);
            log.error("[ANALYSIS] Contract {}: expected ContractAnalysisResultDto JSON "
                    + "(length={}, starts with '{}')", contractId, json.length(), preview(json));
            throw new InvalidAnalysisOutputException(
                    "Model response for contract " + contractId
                            + " was not valid JSON matching ContractAnalysisResultDto", e);
        }

        if (result == null || result.riskScore() == null) {
            log.error("[ANALYSIS] Contract {}: LLM returned invalid structured output", contractId);
            log.error("[ANALYSIS] Contract {}: expected ContractAnalysisResultDto JSON with a riskScore",
                    contractId);
            throw new InvalidAnalysisOutputException(
                    "Model response for contract " + contractId + " parsed but carried no riskScore");
        }

        log.info("[ANALYSIS] Contract {}: risk score extracted = {}", contractId, result.riskScore());
        return result;
    }

    /** Stage 1. Every section is shown to the model exactly once. */
    private Screening screenEverySection(UUID contractId, List<PageIndexNode> sections) {
        List<List<PageIndexNode>> batches =
                SectionBatches.byCharacterBudget(sections, AnalysisAgent::indexWeight, screeningBatchChars);

        log.info("[SCREENING] Contract {}: {} section(s) in {} batch(es)",
                contractId, sections.size(), batches.size());

        long deadline = System.nanoTime() + maxDuration.toNanos();
        List<SectionFinding> findings = new ArrayList<>();
        List<PageIndexNode> unreadable = new ArrayList<>();

        for (int i = 0; i < batches.size(); i++) {
            if (System.nanoTime() > deadline) {
                // Failing here is deliberate. A partial screen would produce a risk score for
                // a document we did not finish reading, and a low score from an unread
                // section is worse than a visible failure.
                throw new AnalysisIncompleteException(("Screening budget of %s exhausted for contract %s "
                        + "after %d/%d batch(es); raise app.analysis.max-duration or "
                        + "app.analysis.screening-batch-chars for documents this large")
                        .formatted(maxDuration, contractId, i, batches.size()));
            }

            List<PageIndexNode> batch = batches.get(i);
            int number = i + 1;
            log.info("[SCREENING] Contract {}: batch {}/{} ({} section(s))",
                    contractId, number, batches.size(), batch.size());

            Optional<List<SectionFinding>> screened =
                    sectionScreener.screen(contractId, batch, number, batches.size());

            if (screened.isEmpty()) {
                unreadable.addAll(batch);
                log.warn("[SCREENING] Contract {}: batch {}/{} unreadable, keeping its summaries as evidence",
                        contractId, number, batches.size());
                continue;
            }
            findings.addAll(screened.get());
            log.info("[SCREENING] Contract {}: batch {}/{} -> {} finding(s)",
                    contractId, number, batches.size(), screened.get().size());
        }

        log.info("[SCREENING] Contract {}: screened {}/{} sections, {} finding(s)",
                contractId, sections.size(), sections.size(), findings.size());
        return new Screening(findings, unreadable, indexById(sections));
    }

    /**
     * Stage 2. Reads the raw text of the most severe findings, highest severity first and
     * document order within a severity, until the evidence budget is spent.
     */
    private String verifyTopFindings(UUID contractId, List<PageIndexNode> sections, Screening screening) {
        List<SectionFinding> ranked = screening.rankedFindings();
        StringBuilder evidence = new StringBuilder();
        int verified = 0;

        for (SectionFinding finding : ranked) {
            if (verified >= maxVerifiedSections || evidence.length() >= maxEvidenceChars) {
                break;
            }
            PageIndexNode node = screening.byId().get(finding.nodeId().trim());
            if (node == null) {
                continue;
            }
            String text = trimTo(node.getRawContext(), maxVerifiedSectionChars);
            if (text.isBlank()) {
                continue;
            }
            appendSection(evidence, node, text);
            verified++;
        }

        // A batch the model could not answer for still has to reach the analysis stage, or
        // those sections would be invisible despite having been screened.
        for (PageIndexNode node : screening.unreadable()) {
            if (evidence.length() >= maxEvidenceChars) {
                break;
            }
            appendSection(evidence, node, trimTo(node.getSummary(), maxVerifiedSectionChars));
        }

        if (evidence.isEmpty()) {
            // Screening found nothing worth reading. The analysis still needs material, so
            // fall back to the section index, which covers the whole document.
            log.warn("[ANALYSIS] Contract {}: screening flagged no sections, falling back to the "
                    + "section index as evidence", contractId);
            for (PageIndexNode node : sections) {
                if (evidence.length() >= maxEvidenceChars) {
                    break;
                }
                appendSection(evidence, node, trimTo(node.getSummary(), maxVerifiedSectionChars));
            }
        }

        String bounded = trimTo(evidence.toString(), maxEvidenceChars);
        log.info("[ANALYSIS] Contract {}: verified {} section(s) of raw text ({} characters of evidence)",
                contractId, verified, bounded.length());
        return bounded;
    }

    private static void appendSection(StringBuilder evidence, PageIndexNode node, String text) {
        evidence.append('[').append(node.getTitle() == null ? "(untitled)" : node.getTitle()).append(']')
                .append(System.lineSeparator())
                .append(text)
                .append(System.lineSeparator())
                .append(System.lineSeparator());
    }

    /** Characters one section contributes to a screening prompt. */
    private static int indexWeight(PageIndexNode node) {
        return 40
                + (node.getTitle() == null ? 0 : node.getTitle().length())
                + (node.getSummary() == null ? 0 : node.getSummary().length());
    }

    private static Map<String, PageIndexNode> indexById(List<PageIndexNode> sections) {
        Map<String, PageIndexNode> byId = new LinkedHashMap<>();
        for (PageIndexNode node : sections) {
            byId.put(node.getId().toString(), node);
        }
        return byId;
    }

    private static String trimTo(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** Screening outcome for one contract. */
    private record Screening(List<SectionFinding> findings, List<PageIndexNode> unreadable,
                             Map<String, PageIndexNode> byId) {

        List<SectionFinding> rankedFindings() {
            return findings.stream()
                    .sorted(Comparator.comparingInt(Screening::severityRank))
                    .toList();
        }

        private static int severityRank(SectionFinding finding) {
            String severity = finding.severity() == null ? "" : finding.severity().trim().toUpperCase();
            return SEVERITY_ORDER.getOrDefault(severity, 3);
        }

        String renderFindings() {
            if (findings.isEmpty()) {
                return "(screening found no section carrying obvious risk)";
            }
            StringBuilder rendered = new StringBuilder();
            for (SectionFinding finding : rankedFindings()) {
                PageIndexNode node = byId.get(finding.nodeId().trim());
                rendered.append("- [").append(finding.severity()).append("] ")
                        .append(node == null || node.getTitle() == null ? "section" : node.getTitle())
                        .append(": ").append(finding.concern())
                        .append(System.lineSeparator());
            }
            return rendered.toString();
        }
    }

    /**
     * Enforces the output contract before any parsing is attempted.
     *
     * <p>A fenced object is normalised rather than rejected: the fence is a formatting habit
     * around otherwise valid JSON. Anything that is not a JSON object, in particular contract
     * prose, is rejected here so the failure names the real problem instead of surfacing as a
     * parser error.
     */
    private String requireJsonObject(UUID contractId, String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            log.error("[ANALYSIS] Contract {}: LLM returned invalid structured output", contractId);
            log.error("[ANALYSIS] Contract {}: expected ContractAnalysisResultDto JSON, got an empty response",
                    contractId);
            throw new InvalidAnalysisOutputException(
                    "AnalysisAgent received an empty response from the model for contract " + contractId);
        }

        String candidate = stripCodeFence(rawResponse.strip());
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            log.error("[ANALYSIS] Contract {}: LLM returned invalid structured output", contractId);
            log.error("[ANALYSIS] Contract {}: expected ContractAnalysisResultDto JSON "
                            + "(length={}, starts with '{}')",
                    contractId, candidate.length(), preview(candidate));
            throw new InvalidAnalysisOutputException(
                    "Model response for contract " + contractId + " was not a JSON object");
        }
        return candidate;
    }

    private static String stripCodeFence(String response) {
        if (!response.startsWith("```")) {
            return response;
        }
        int firstNewline = response.indexOf('\n');
        int closingFence = response.lastIndexOf("```");
        if (firstNewline < 0 || closingFence <= firstNewline) {
            return response;
        }
        return response.substring(firstNewline + 1, closingFence).strip();
    }

    /** First few characters only, enough to tell JSON from prose without logging content. */
    private static String preview(String response) {
        String trimmed = response.strip();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40);
    }

    /** The model produced something that is not a usable {@link ContractAnalysisResultDto}. */
    public static class InvalidAnalysisOutputException extends RuntimeException {
        public InvalidAnalysisOutputException(String message) {
            super(message);
        }

        public InvalidAnalysisOutputException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The document could not be screened in full within its time budget. Retryable. */
    public static class AnalysisIncompleteException extends RuntimeException {
        public AnalysisIncompleteException(String message) {
            super(message);
        }
    }
}
