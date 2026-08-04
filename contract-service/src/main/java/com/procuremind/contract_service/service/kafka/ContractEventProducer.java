package com.procuremind.contract_service.service.kafka;

import com.procuremind.common.dto.ContractUploadedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContractEventProducer {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishContractUploadEvent(UUID contractId, String vendorName, String filename, String minioObjName) {
        try {
            ContractUploadedEvent event = new ContractUploadedEvent(contractId, filename, minioObjName);
            kafkaTemplate.send("contract.uploaded", contractId.toString(), event);

            log.info("Published contract.uploaded event for ID: {}", contractId);
        } catch (Exception e) {
            log.error("Failed to publish Kafka event for contract: {}", contractId, e);
            throw new RuntimeException("Event publishing failed", e);
        }
    }
}
