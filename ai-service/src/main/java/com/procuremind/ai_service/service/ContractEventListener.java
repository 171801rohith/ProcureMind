package com.procuremind.ai_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractEventListener {

    private final PdfParsingService pdfParsingService;
    private final IndexingService indexingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = "contract.uploaded", groupId = "ai-processing-group")
    public void handleContractUploaded(String eventJson) {
        try {
            JsonNode event = objectMapper.readTree(eventJson);
            UUID documentId = UUID.fromString(event.get("contractId").asText());
            String minioObjName = event.get("minioObjName").asText();

            log.info("Received event for contract [{}]. Triggering PDF Parsing...", documentId);
            pdfParsingService.parseAndIndexPdf(documentId, minioObjName);

            indexingService.indexContractNodes(documentId);
            log.info("Done parsing event for contract [{}]. Triggering Indexing Agent...", documentId);

        } catch (Exception e) {
            log.error("Error processing contract uploaded event", e);
        }
    }
}
