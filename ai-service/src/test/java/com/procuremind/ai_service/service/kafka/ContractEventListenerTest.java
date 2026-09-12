package com.procuremind.ai_service.service.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import com.procuremind.ai_service.service.AnalysisService;
import com.procuremind.ai_service.service.IndexingService;
import com.procuremind.ai_service.service.PdfParsingService;
import com.procuremind.common.dto.ContractUploadedEvent;
import com.procuremind.common.dto.PageIndexedEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * These listeners used to catch everything, which made Spring Kafka's error handler
 * unreachable: every failure committed the offset and left the contract pinned at UPLOADED
 * or INDEXED forever. The propagation asserted here is what makes retry and dead-lettering
 * possible at all.
 */
@ExtendWith(MockitoExtension.class)
class ContractEventListenerTest {

    private static final UUID CONTRACT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Mock
    PdfParsingService pdfParsingService;

    @Mock
    IndexingService indexingService;

    @Mock
    AnalysisService analysisService;

    @InjectMocks
    ContractEventListener listener;

    @Test
    void parsingRunsBeforeIndexingForAnUploadedContract() {
        listener.handleContractUploaded(new ContractUploadedEvent(CONTRACT_ID, "msa.pdf", "obj_msa.pdf"));

        InOrder order = inOrder(pdfParsingService, indexingService);
        order.verify(pdfParsingService).parseAndIndexPdf(CONTRACT_ID, "obj_msa.pdf");
        order.verify(indexingService).indexContractNodes(CONTRACT_ID);
    }

    @Test
    void aParsingFailureStopsThePipelineAndReachesTheErrorHandler() {
        willThrow(new RuntimeException("PDF Parsing failed"))
                .given(pdfParsingService).parseAndIndexPdf(CONTRACT_ID, "obj_msa.pdf");

        assertThatThrownBy(() -> listener.handleContractUploaded(
                new ContractUploadedEvent(CONTRACT_ID, "msa.pdf", "obj_msa.pdf")))
                .isInstanceOf(RuntimeException.class);

        // Indexing a document that was never parsed would publish an empty index.
        verifyNoInteractions(indexingService);
    }

    @Test
    void anIndexingFailureReachesTheErrorHandler() {
        willThrow(new IndexingService.IndexingIncompleteException("budget exhausted"))
                .given(indexingService).indexContractNodes(CONTRACT_ID);

        assertThatThrownBy(() -> listener.handleContractUploaded(
                new ContractUploadedEvent(CONTRACT_ID, "msa.pdf", "obj_msa.pdf")))
                .isInstanceOf(IndexingService.IndexingIncompleteException.class);
    }

    @Test
    void anAnalysisFailureReachesTheErrorHandler() {
        willThrow(new IllegalStateException("LLM unreachable"))
                .given(analysisService).processContract(CONTRACT_ID);

        assertThatThrownBy(() -> listener.handleContractIndexed(new PageIndexedEvent(CONTRACT_ID, "INDEXED")))
                .isInstanceOf(IllegalStateException.class);
    }
}
