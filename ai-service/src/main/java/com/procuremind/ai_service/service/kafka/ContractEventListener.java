package com.procuremind.ai_service.service.kafka;

import com.procuremind.ai_service.service.AnalysisService;
import com.procuremind.ai_service.service.IndexingService;
import com.procuremind.ai_service.service.PdfParsingService;
import com.procuremind.common.dto.ContractUploadedEvent;
import com.procuremind.common.dto.PageIndexedEvent;
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
    private final AnalysisService analysisService;

    @KafkaListener(topics = "contract.uploaded", groupId = "ai-processing-group")
    public void handleContractUploaded(ContractUploadedEvent event) {
        try {
            UUID documentId = event.contractId();
            String minioObjName = event.minioObjName();

            log.info("Received event for contract [{}]. Triggering PDF Parsing...", documentId);
            pdfParsingService.parseAndIndexPdf(documentId, minioObjName);

            indexingService.indexContractNodes(documentId);
            log.info("Done parsing event for contract [{}]. Triggering Indexing Agent...", documentId);

        } catch (Exception e) {
            log.error("Error processing contract uploaded event", e);
        }
    }

    @KafkaListener(topics = "contract.indexed", groupId = "ai-processing-group")
    public void handleContractIndexed(PageIndexedEvent event) {
        try {
            UUID contractId = event.contractId();

            log.info("Received event for indexed contract [{}]. Triggering contract Analysis...", contractId);
            analysisService.processContract(contractId);

        } catch (Exception e) {
            log.error("Error processing contract indexed event", e);
        }
    }
}
