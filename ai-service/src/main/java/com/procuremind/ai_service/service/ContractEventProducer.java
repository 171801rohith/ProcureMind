package com.procuremind.ai_service.service;

import com.procuremind.ai_service.dto.PageIndexedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ContractEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TOPIC_INDEXED = "contract.indexed";

    public void publishPageIndexed(UUID documentId) {
        PageIndexedEvent event = new PageIndexedEvent(documentId, "INDEXED");
        kafkaTemplate.send(TOPIC_INDEXED, documentId.toString(), event);
    }
}
