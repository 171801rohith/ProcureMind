package com.procuremind.ai_service.service.kafka;

import com.procuremind.common.dto.PageIndexedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ContractEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPageIndexed(UUID documentId) {
        PageIndexedEvent event = new PageIndexedEvent(documentId, "INDEXED");
        kafkaTemplate.send("contract.indexed", documentId.toString(), event);
    }

    public void publishAnalysisCompleted(UUID documentId) {
        PageIndexedEvent event = new PageIndexedEvent(documentId, "ANALYSIS_COMPLETED");
        kafkaTemplate.send("contract.analyzed", documentId.toString(), event);
    }
}
