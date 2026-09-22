package com.procuremind.contract_service.service.kafka;

import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Applies {@code contracts.status} transitions reported by the pipeline.
 *
 * <p>Used both by {@link ContractEventListener} for normal lifecycle events, and by
 * {@link com.procuremind.contract_service.config.KafkaErrorHandlingConfig}'s dead-letter
 * recoverer: a message that exhausts its retries and is routed to a {@code .DLT} topic must
 * still leave the contract visibly FAILED rather than stuck at whatever status it had before
 * the poison message, which is what happened before this class existed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContractStatusUpdater {

    /** Lifecycle order. A status is only applied if it is at or beyond the current one. */
    private static final List<String> LIFECYCLE = List.of("UPLOADED", "INDEXED", "ANALYZED");

    public static final String FAILED = "FAILED";

    private final ContractRepository contractRepository;

    @Transactional
    public void advance(UUID contractId, String newStatus) {
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

    /** Marks a contract FAILED from a raw Kafka record key, tolerating a missing/invalid id. */
    public void markFailedByKey(String rawContractId) {
        if (rawContractId == null || rawContractId.isBlank()) {
            log.error("Cannot mark contract FAILED: dead-lettered record carried no key");
            return;
        }
        try {
            advance(UUID.fromString(rawContractId), FAILED);
        } catch (IllegalArgumentException e) {
            log.error("Cannot mark contract FAILED: record key '{}' is not a contract UUID", rawContractId);
        }
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
