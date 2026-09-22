package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.agent.AnalysisAgent;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto.RiskItem;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import com.procuremind.ai_service.service.kafka.ContractEventProducer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Analysis is the most expensive stage in the pipeline, so the idempotency guard is what
 * stops a Kafka redelivery from paying for a second LLM run and writing a duplicate row.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    private static final UUID CONTRACT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @Mock
    ContractAnalysisRepository analysisRepository;

    @Mock
    ContractMetadataRepository metadataRepository;

    @Mock
    ContractEventProducer eventProducer;

    @Mock
    AnalysisAgent analysisAgent;

    @InjectMocks
    AnalysisService analysisService;

    @Test
    void anAlreadyAnalysedContractSkipsTheLlmButStillAnnouncesCompletion() {
        given(analysisRepository.existsByContractId(CONTRACT_ID)).willReturn(true);

        analysisService.processContract(CONTRACT_ID);

        // The expensive work is skipped, but the event still goes out: without it a contract
        // that was marked FAILED before a successful reprocess could never reach ANALYZED.
        verifyNoInteractions(analysisAgent);
        verifyNoInteractions(metadataRepository);
        verify(eventProducer).publishAnalysisCompleted(CONTRACT_ID);
    }

    @Test
    void theAgentResultIsPersistedAsAnalysisMetadataAndLinkedRisks() {
        given(analysisRepository.existsByContractId(CONTRACT_ID)).willReturn(false);
        given(analysisAgent.execute(CONTRACT_ID)).willReturn(new ContractAnalysisResultDto(
                7.5, "MSA", 120000.0,
                List.of(new RiskItem("HIGH", "Uncapped liability."), new RiskItem("LOW", "Standard notice period.")),
                "Renegotiate the indemnity cap."));

        analysisService.processContract(CONTRACT_ID);

        ArgumentCaptor<ContractAnalysis> analysis = ArgumentCaptor.forClass(ContractAnalysis.class);
        verify(analysisRepository).save(analysis.capture());
        assertThat(analysis.getValue().getRiskScore()).isEqualTo(7.5);
        assertThat(analysis.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(analysis.getValue().getRecommendation()).isEqualTo("Renegotiate the indemnity cap.");
        assertThat(analysis.getValue().getRisks()).hasSize(2);
        // Each risk points back at its parent, which is what makes the cascade insert work.
        assertThat(analysis.getValue().getRisks())
                .allSatisfy(risk -> assertThat(risk.getAnalysis()).isSameAs(analysis.getValue()));

        ArgumentCaptor<ContractMetadata> metadata = ArgumentCaptor.forClass(ContractMetadata.class);
        verify(metadataRepository).save(metadata.capture());
        assertThat(metadata.getValue().getContractType()).isEqualTo("MSA");
        assertThat(metadata.getValue().getAmount()).isEqualTo(120000.0);

        verify(eventProducer).publishAnalysisCompleted(CONTRACT_ID);
    }

    @Test
    void anAgentFailurePropagatesSoTheRecordCanBeRetried() {
        given(analysisRepository.existsByContractId(CONTRACT_ID)).willReturn(false);
        given(analysisAgent.execute(CONTRACT_ID)).willThrow(new IllegalStateException("LLM unreachable"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> analysisService.processContract(CONTRACT_ID))
                .isInstanceOf(IllegalStateException.class);

        // Nothing half-written and nothing announced.
        verify(analysisRepository, org.mockito.Mockito.never()).save(any());
        verifyNoInteractions(eventProducer);
    }
}
