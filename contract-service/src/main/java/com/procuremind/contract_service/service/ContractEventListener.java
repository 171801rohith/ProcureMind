package com.procuremind.contract_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.procuremind.contract_service.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;


import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {
    private final ContractRepository contractRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    @KafkaListener(topics = "contract.indexed", groupId = "contract-processing-group")
    public void handleContractIndexed(String eventJson) {
        updateContractStatus(eventJson, "INDEXED");
    }

    @Transactional
    @KafkaListener(topics = "contract.analyzed", groupId = "contract-processing-group")
    public void handleContractAnalyzed(String eventJson) {
        updateContractStatus(eventJson, "ANALYZED");
    }

    private void updateContractStatus(String eventJson, String newStatus) {
        try {
            JsonNode event = objectMapper.readTree(eventJson);
            UUID docId = UUID.fromString(event.get("contractId").asText());

            contractRepository.findById(docId).ifPresent(contract -> {
                contract.setStatus(newStatus);
                contractRepository.save(contract);
                log.info("Updated contract [{}] status to {}", docId, newStatus);
            });

        } catch (Exception e) {
            log.error("Failed to update contract status from event: {}", eventJson, e);
        }
    }
}
