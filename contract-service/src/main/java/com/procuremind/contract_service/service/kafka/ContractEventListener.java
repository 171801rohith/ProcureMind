package com.procuremind.contract_service.service.kafka;

import com.procuremind.common.dto.PageIndexedEvent;
import com.procuremind.contract_service.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {
    private final ContractRepository contractRepository;

    @Transactional
    @KafkaListener(topics = "contract.indexed", groupId = "contract-processing-group")
    public void handleContractIndexed(PageIndexedEvent event) {
        updateContractStatus(event, "INDEXED");
    }

    @Transactional
    @KafkaListener(topics = "contract.analyzed", groupId = "contract-processing-group")
    public void handleContractAnalyzed(PageIndexedEvent event) {
        updateContractStatus(event, "ANALYZED");
    }

    private void updateContractStatus(PageIndexedEvent event, String newStatus) {
        try {
            UUID docId = event.contractId();

            contractRepository.findById(docId).ifPresent(contract -> {
                contract.setStatus(newStatus);
                contractRepository.save(contract);
                log.info("Updated contract [{}] status to {}", docId, newStatus);
            });

        } catch (Exception e) {
            log.error("Failed to update contract status from event: {}", event, e);
        }
    }
}
