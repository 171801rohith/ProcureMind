package com.procuremind.contract_service.service.kafka;

import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import com.procuremind.common.dto.PageIndexedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Advances {@code contracts.status} as the ai-service pipeline reports progress.
 *
 * <p>Two behaviours were fixed here:
 * <ul>
 *   <li>Failures are no longer swallowed — they propagate so the container's error handler
 *       can retry and then dead-letter the record instead of losing it silently.</li>
 *   <li>The status transition is <b>monotonic</b>. A redelivered {@code contract.indexed}
 *       used to overwrite an already-ANALYZED contract back to INDEXED; the lifecycle now
 *       only ever moves forward, except that a FAILED contract can be recovered by a later
 *       success.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {

    /** Lifecycle order. A status is only applied if it is at or beyond the current one. */
    private static final List<String> LIFECYCLE = List.of("UPLOADED", "INDEXED", "ANALYZED");

    static final String FAILED = "FAILED";

    private final ContractRepository contractRepository;

    @Transactional
    @KafkaListener(topics = "contract.indexed", groupId = "contract-processing-group")
    public void handleContractIndexed(PageIndexedEvent event) {
        updateContractStatus(event.contractId(), "INDEXED");
    }

    @Transactional
    @KafkaListener(topics = "contract.analyzed", groupId = "contract-processing-group")
    public void handleContractAnalyzed(PageIndexedEvent event) {
        updateContractStatus(event.contractId(), "ANALYZED");
    }

    /**
     * Terminal failure reported by ai-service after its retries were exhausted, so a
     * contract that cannot be processed stops looking like one that is merely slow.
     */
    @Transactional
    @KafkaListener(topics = "contract.failed", groupId = "contract-processing-group")
    public void handleContractFailed(PageIndexedEvent event) {
        updateContractStatus(event.contractId(), FAILED);
    }

    private void updateContractStatus(UUID contractId, String newStatus) {
        Contract contract = contractRepository.findById(contractId).orElse(null);
        if (contract == null) {
            log.warn("Ignoring status '{}' for unknown contract [{}]", newStatus, contractId);
            return;
        }
        if (!shouldAdvance(contract.getStatus(), newStatus)) {
            log.info("Ignoring status '{}' for contract [{}] already at '{}'",
                    newStatus, contractId, contract.getStatus());
            return;
        }
        contract.setStatus(newStatus);
        contractRepository.save(contract);
        log.info("Updated contract [{}] status to {}", contractId, newStatus);
    }

    /**
     * Visible for testing. The lifecycle only moves forward, with one exception: a contract
     * sitting at FAILED may be recovered by a later success.
     *
     * <p>That exception matters because a failure is often environmental, such as the model
     * being unavailable. Without it, reprocessing a contract produced a completed analysis
     * that the dashboard still displayed as FAILED, with no way back other than editing the
     * row by hand. The trade-off is that a redelivered older success can also clear FAILED;
     * that is acceptable because the pipeline reprocesses a contract end to end and only
     * publishes a success event when the analysis actually completed.
     */
    static boolean shouldAdvance(String current, String candidate) {
        if (FAILED.equals(candidate)) {
            return !FAILED.equals(current);
        }
        if (FAILED.equals(current)) {
            return LIFECYCLE.contains(candidate);
        }
        return LIFECYCLE.indexOf(candidate) > LIFECYCLE.indexOf(current);
    }
}
