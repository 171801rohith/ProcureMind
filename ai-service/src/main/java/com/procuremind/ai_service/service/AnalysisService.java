package com.procuremind.ai_service.service;

import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.agent.AnalysisAgent;
import com.procuremind.ai_service.dto.ContractAnalysisResult;
import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
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
    private final ContractEventProducer eventProducer;
    private final AnalysisAgent analysisAgent;

    @Transactional
    public void processContract(UUID contractId) {
        log.info("Starting orchestrated analysis for contract: {}", contractId);

        if (analysisRepository.existsByContractId(contractId)) {
            log.info("Contract {} already analyzed. Skipping.", contractId);
            return;
        }

        ContractAnalysisResult aiResponse = analysisAgent.execute(contractId);

        ContractAnalysis analysis = ContractAnalysis.builder()
                .contractId(contractId)
                .vendorName(aiResponse.vendorName())
                .riskScore(aiResponse.riskScore())
                .recommendation(aiResponse.recommendation())
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();

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

        eventProducer.publishAnalysisCompleted(contractId);
        log.info("Analysis saved and event published for contract: {}", contractId);
    }

}
