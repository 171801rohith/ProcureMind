package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.agent.IndexingAgent;
import com.procuremind.ai_service.dto.NodeSummary;
import com.procuremind.ai_service.entity.PageIndexNode;
import com.procuremind.ai_service.service.IndexingService.IndexingIncompleteException;
import com.procuremind.ai_service.service.kafka.ContractEventProducer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers the properties the Kafka-driven indexing loop has to hold: it resumes rather than
 * restarts, it commits each node as it goes, it survives a single bad node, and it fails
 * loudly in the two situations a redelivery can actually fix.
 */
@ExtendWith(MockitoExtension.class)
class IndexingServiceTest {

    private static final UUID DOCUMENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    PageIndexNodeRepository nodeRepository;

    @Mock
    IndexingAgent indexingAgent;

    @Mock
    ContractEventProducer eventProducer;

    IndexingService indexingService;

    @BeforeEach
    void setUp() {
        indexingService = new IndexingService(nodeRepository, indexingAgent, eventProducer);
        setBudget(Duration.ofMinutes(20));
        ReflectionTestUtils.setField(indexingService, "interCallDelay", Duration.ZERO);
    }

    @Test
    void anAlreadyIndexedContractIsANoOpThatStillAnnouncesCompletion() {
        given(nodeRepository.findByDocumentIdAndSummaryIsNull(DOCUMENT_ID)).willReturn(List.of());

        indexingService.indexContractNodes(DOCUMENT_ID);

        // Idempotency: a redelivered contract.uploaded must not re-run the LLM, but the
        // downstream stage still has to be told the contract is indexed.
        verifyNoInteractions(indexingAgent);
        verify(nodeRepository, never()).save(any());
        verify(eventProducer).publishPageIndexed(DOCUMENT_ID);
    }

    @Test
    void onlyUnsummarisedNodesAreProcessedAndEachIsCommittedAsItGoes() {
        List<PageIndexNode> pending = nodes(3);
        given(nodeRepository.findByDocumentIdAndSummaryIsNull(DOCUMENT_ID)).willReturn(pending);
        given(indexingAgent.summarize(any())).willReturn(new NodeSummary("Payment Terms", "Net 30."));

        indexingService.indexContractNodes(DOCUMENT_ID);

        // One save per node, not one saveAll at the end: a crash mid-contract keeps the
        // work already done, so the retry resumes from the remaining nodes.
        ArgumentCaptor<PageIndexNode> saved = ArgumentCaptor.forClass(PageIndexNode.class);
        verify(nodeRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(node -> {
            assertThat(node.getTitle()).isEqualTo("Payment Terms");
            assertThat(node.getSummary()).isEqualTo("Net 30.");
        });
        verify(eventProducer).publishPageIndexed(DOCUMENT_ID);
    }

    @Test
    void oneFailingNodeDoesNotAbandonTheRestOfTheContract() {
        List<PageIndexNode> pending = nodes(3);
        given(nodeRepository.findByDocumentIdAndSummaryIsNull(DOCUMENT_ID)).willReturn(pending);
        given(indexingAgent.summarize(any()))
                .willReturn(new NodeSummary("Scope", "Covers widgets."))
                .willThrow(new IllegalStateException("model returned malformed JSON"))
                .willReturn(new NodeSummary("Term", "Three years."));

        indexingService.indexContractNodes(DOCUMENT_ID);

        verify(nodeRepository, times(2)).save(any());
        // The failed node keeps its null summary, so reprocessing this contract retries it.
        assertThat(pending.get(1).getSummary()).isNull();
        verify(eventProducer).publishPageIndexed(DOCUMENT_ID);
    }

    @Test
    void aWhollyFailedContractThrowsInsteadOfAdvancingWithAnEmptyIndex() {
        given(nodeRepository.findByDocumentIdAndSummaryIsNull(DOCUMENT_ID)).willReturn(nodes(2));
        given(indexingAgent.summarize(any())).willThrow(new IllegalStateException("LLM unreachable"));

        assertThatThrownBy(() -> indexingService.indexContractNodes(DOCUMENT_ID))
                .isInstanceOf(IndexingIncompleteException.class)
                .hasMessageContaining("No node could be indexed");

        // Nothing is published: the contract must not reach INDEXED with no summaries.
        verifyNoInteractions(eventProducer);
    }

    @Test
    void anExhaustedTimeBudgetThrowsBeforeTheConsumerCanBeEvicted() {
        // A zero budget is already spent when the loop starts, which is the same state a
        // long contract reaches part-way through; the throw hands the record back to the
        // error handler while the partial work stays committed.
        setBudget(Duration.ZERO);
        given(nodeRepository.findByDocumentIdAndSummaryIsNull(DOCUMENT_ID)).willReturn(nodes(2));

        assertThatThrownBy(() -> indexingService.indexContractNodes(DOCUMENT_ID))
                .isInstanceOf(IndexingIncompleteException.class)
                .hasMessageContaining("budget");

        verifyNoInteractions(indexingAgent);
        verifyNoInteractions(eventProducer);
    }

    private void setBudget(Duration budget) {
        ReflectionTestUtils.setField(indexingService, "maxDuration", budget);
    }

    private static List<PageIndexNode> nodes(int count) {
        List<PageIndexNode> nodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            nodes.add(PageIndexNode.builder()
                    .id(UUID.randomUUID())
                    .documentId(DOCUMENT_ID)
                    .level(2)
                    .nodeOrder(i)
                    .rawContext("Section " + i + " body text.")
                    .build());
        }
        return nodes;
    }
}
