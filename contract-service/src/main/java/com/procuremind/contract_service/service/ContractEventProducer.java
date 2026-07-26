package com.procuremind.contract_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractEventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publishContractUploadEvent(UUID contractId, String filename, String minioObjName) {
        try {
            Map<String, String> event = new HashMap<>();
            event.put("contractId", contractId.toString());
            event.put("minioObjName", minioObjName);
            event.put("filename", filename);

            String eventJson = objectMapper.writeValueAsString(event);
            kafkaTemplate.send("contract.uploaded", contractId.toString(), eventJson);

            log.info("Published contract.uploaded event for ID: {}", contractId);
        } catch (Exception e) {
            log.error("Failed to publish Kafka event for contract: {}", contractId, e);
            throw new RuntimeException("Event publishing failed", e);
        }
    }
}
