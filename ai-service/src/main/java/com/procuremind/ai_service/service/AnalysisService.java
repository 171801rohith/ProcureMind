package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.agent.AnalysisAgent;
import com.procuremind.ai_service.dto.ContractAnalysisResultDto;
import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import com.procuremind.ai_service.service.kafka.ContractEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final ContractAnalysisRepository analysisRepository;
    private final ContractMetadataRepository metadataRepository;
    private final ContractEventProducer eventProducer;
    private final AnalysisAgent analysisAgent;

    @Transactional
    public void processContract(UUID contractId) {
        log.info("[ANALYSIS] Contract {}: starting analysis", contractId);

        if (analysisRepository.existsByContractId(contractId)) {
            // Re-announce rather than return silently. The expensive part is skipped, but a
            // contract whose analysis succeeded after an earlier failure would otherwise stay
            // at FAILED forever: the status only moves when this event is published, and the
            // listener that consumes it ignores repeats.
            log.info("[ANALYSIS] Contract {}: already analysed, re-publishing completion", contractId);
            eventProducer.publishAnalysisCompleted(contractId);
            return;
        }

        // The agent screens every section of the contract and then reads the most severe
        // ones in full; the [SCREENING] lines show the coverage it achieved.
        log.info("[ANALYSIS] Contract {}: screening all sections", contractId);
        ContractAnalysisResultDto aiResponse = analysisAgent.execute(contractId);

        ContractAnalysis analysis = ContractAnalysis.builder()
                .contractId(contractId)
                .riskScore(aiResponse.riskScore())
                .recommendation(aiResponse.recommendation())
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();

        ContractMetadata metadata = ContractMetadata.builder()
                .contractId(contractId)
                .contractType(aiResponse.contractType())
                .amount(aiResponse.amount())
                .build();

        metadataRepository.save(metadata);

        List<AnalysisRisk> risks = aiResponse.risks().stream()
                .map(dto ->
                        AnalysisRisk.builder()
                                .analysis(analysis)
                                .severity(dto.severity())
                                .description(dto.description())
                                .build()
                ).toList();
                analysis.setRisks(risks);
        analysisRepository.save(analysis);

        log.info("[ANALYSIS] Contract {}: analysis persisted successfully (riskScore={}, risks={}, type={})",
                contractId, aiResponse.riskScore(), risks.size(), aiResponse.contractType());

        eventProducer.publishAnalysisCompleted(contractId);
        log.info("[ANALYSIS] Contract {}: completed", contractId);
    }

}
