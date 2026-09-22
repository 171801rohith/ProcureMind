package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.procuremind.ai_service.Repository.AnalysisRiskRepository;
import com.procuremind.ai_service.Repository.ContractAnalysisRepository;
import com.procuremind.ai_service.Repository.ContractMetadataRepository;
import com.procuremind.ai_service.Repository.PageIndexNodeRepository;
import com.procuremind.ai_service.dto.AnalysisResponseDto;
import com.procuremind.ai_service.dto.DashboardDtos;
import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers finding #11's DTO-alignment fix: {@link AnalysisResponseDto} and
 * {@link DashboardDtos.FinancialExposureDto} now carry fields the frontend already reads
 * (client.js / ExposureChart.jsx) but the backend never populated.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisQueryServiceTest {

    private static final UUID CONTRACT_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock
    ContractAnalysisRepository analysisRepository;

    @Mock
    AnalysisRiskRepository riskRepository;

    @Mock
    PageIndexNodeRepository nodeRepository;

    @Mock
    ContractMetadataRepository metadataRepository;

    AnalysisQueryService service() {
        return new AnalysisQueryService(analysisRepository, riskRepository, nodeRepository, metadataRepository);
    }

    private ContractAnalysis analysisWith(List<AnalysisRisk> risks) {
        ContractAnalysis analysis = ContractAnalysis.builder()
                .contractId(CONTRACT_ID)
                .riskScore(7.5)
                .recommendation("Renegotiate the indemnity cap.")
                .status("COMPLETED")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        risks.forEach(r -> r.setAnalysis(analysis));
        analysis.setRisks(risks);
        return analysis;
    }

    @Test
    void getAnalysisPopulatesContractTypeAndAmountFromMetadata() {
        given(analysisRepository.findByContractId(CONTRACT_ID))
                .willReturn(Optional.of(analysisWith(List.of())));
        given(metadataRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(
                ContractMetadata.builder().contractId(CONTRACT_ID).contractType("MSA").amount(120000.0).build()));

        AnalysisResponseDto dto = service().getAnalysis(CONTRACT_ID).orElseThrow();

        assertThat(dto.contractType()).isEqualTo("MSA");
        assertThat(dto.amount()).isEqualTo(120000.0);
    }

    @Test
    void getAnalysisToleratesMissingMetadataInsteadOfThrowing() {
        given(analysisRepository.findByContractId(CONTRACT_ID))
                .willReturn(Optional.of(analysisWith(List.of())));
        given(metadataRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        AnalysisResponseDto dto = service().getAnalysis(CONTRACT_ID).orElseThrow();

        assertThat(dto.contractType()).isNull();
        assertThat(dto.amount()).isNull();
    }

    @Test
    void getAnalysisDerivesHighRiskCountFromTheRisksThemselves() {
        List<AnalysisRisk> risks = List.of(
                AnalysisRisk.builder().severity("HIGH").description("Uncapped liability.").build(),
                AnalysisRisk.builder().severity("HIGH").description("No termination for convenience.").build(),
                AnalysisRisk.builder().severity("LOW").description("Standard notice period.").build());
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(analysisWith(risks)));
        given(metadataRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        AnalysisResponseDto dto = service().getAnalysis(CONTRACT_ID).orElseThrow();

        assertThat(dto.highRiskCount()).isEqualTo(2L);
    }

    @Test
    void getAnalysisDerivesSummaryFromRiskScoreAndTheMostSevereFinding() {
        List<AnalysisRisk> risks = List.of(
                AnalysisRisk.builder().severity("LOW").description("Standard notice period.").build(),
                AnalysisRisk.builder().severity("HIGH").description("Uncapped liability exposure.").build(),
                AnalysisRisk.builder().severity("MEDIUM").description("Auto-renewal without notice.").build());
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(analysisWith(risks)));
        given(metadataRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        AnalysisResponseDto dto = service().getAnalysis(CONTRACT_ID).orElseThrow();

        assertThat(dto.summary())
                .contains("7.5")
                .contains("HIGH")
                .contains("Uncapped liability exposure.");
    }

    @Test
    void getAnalysisSummaryHandlesAContractWithNoRisksAtAll() {
        given(analysisRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.of(analysisWith(List.of())));
        given(metadataRepository.findByContractId(CONTRACT_ID)).willReturn(Optional.empty());

        AnalysisResponseDto dto = service().getAnalysis(CONTRACT_ID).orElseThrow();

        assertThat(dto.summary()).contains("7.5").contains("No risks identified");
        assertThat(dto.highRiskCount()).isZero();
    }

    @Test
    void getFinancialExposureCarriesVendorNameAndFileNameThroughFromTheProjection() {
        var projection = financialExposureProjection(
                CONTRACT_ID, "MSA", 50000.0, 6.0, 1L, "Acme Corp", "msa-2024.pdf");
        given(analysisRepository.getFinancialExposureData()).willReturn(List.of(projection));

        List<DashboardDtos.FinancialExposureDto> result = service().getFinancialExposure();

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.vendorName()).isEqualTo("Acme Corp");
            assertThat(dto.fileName()).isEqualTo("msa-2024.pdf");
            assertThat(dto.contractType()).isEqualTo("MSA");
        });
    }

    private static ContractAnalysisRepository.FinancialExposureProjection financialExposureProjection(
            UUID contractId, String contractType, Double amount, Double riskScore, Long highRiskCount,
            String vendorName, String fileName) {
        return new ContractAnalysisRepository.FinancialExposureProjection() {
            public UUID getContractId() {
                return contractId;
            }

            public String getContractType() {
                return contractType;
            }

            public Double getAmount() {
                return amount;
            }

            public Double getRiskScore() {
                return riskScore;
            }

            public Long getHighRiskCount() {
                return highRiskCount;
            }

            public String getVendorName() {
                return vendorName;
            }

            public String getFileName() {
                return fileName;
            }
        };
    }
}
