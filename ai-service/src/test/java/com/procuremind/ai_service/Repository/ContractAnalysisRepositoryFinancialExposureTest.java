package com.procuremind.ai_service.Repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.procuremind.ai_service.entity.AnalysisRisk;
import com.procuremind.ai_service.entity.ContractAnalysis;
import com.procuremind.ai_service.entity.ContractMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves finding #11's fix against the real database, not just against the compiler.
 *
 * <p>{@code getFinancialExposureData()} now joins {@code ContractRef} — an ad-hoc JPQL
 * {@code ON} join against an entity with no mapped association to {@link ContractAnalysis} —
 * to pull {@code vendor_name}/{@code filename} out of the {@code contracts} table. That kind
 * of unrelated-entity join is exactly the class of thing that type-checks but silently returns
 * nulls or throws at runtime, so this exercises it against the real local Postgres instead of
 * trusting the JPQL by inspection.
 *
 * <p>The {@code contracts} row is seeded with plain JDBC, never through
 * {@code ContractRefRepository#save} — ai-service must never write that table
 * (contract-service is the sole writer; see {@link com.procuremind.ai_service.entity.ContractRef}).
 * The whole test runs inside a rolled-back transaction, so nothing persists afterwards.
 */
@SpringBootTest
@Transactional
class ContractAnalysisRepositoryFinancialExposureTest {

    @Autowired
    private ContractAnalysisRepository analysisRepository;

    @Autowired
    private ContractMetadataRepository metadataRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void financialExposureProjectionJoinsVendorNameAndFileNameFromTheContractsTable() {
        UUID contractId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO contracts (id, filename, minio_object_name, vendor_name, status, uploaded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                contractId, "msa-2024.pdf", "obj-key-1", "Acme Corp", "ANALYZED",
                Timestamp.valueOf(LocalDateTime.now()));

        metadataRepository.save(ContractMetadata.builder()
                .contractId(contractId)
                .contractType("MSA")
                .amount(50000.0)
                .build());

        ContractAnalysis analysis = ContractAnalysis.builder()
                .contractId(contractId)
                .riskScore(7.0)
                .recommendation("Renegotiate the indemnity cap.")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build();
        analysis.setRisks(List.of(AnalysisRisk.builder()
                .analysis(analysis)
                .severity("HIGH")
                .description("Uncapped liability.")
                .build()));
        analysisRepository.save(analysis);

        List<ContractAnalysisRepository.FinancialExposureProjection> rows =
                analysisRepository.getFinancialExposureData();

        assertThat(rows)
                .filteredOn(p -> p.getContractId().equals(contractId))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.getVendorName()).isEqualTo("Acme Corp");
                    assertThat(p.getFileName()).isEqualTo("msa-2024.pdf");
                    assertThat(p.getHighRiskCount()).isEqualTo(1L);
                    assertThat(p.getContractType()).isEqualTo("MSA");
                    assertThat(p.getAmount()).isEqualTo(50000.0);
                });
    }

    @Test
    void financialExposureProjectionToleratesAContractRowThatDoesNotExistYet() {
        // LEFT JOIN, not JOIN: a contract_metadata/contract_analysis pair whose `contracts`
        // row is missing (e.g. deleted, or a race with contract-service) must not drop the
        // whole record from the dashboard -- it should just come back with null vendor/file.
        UUID contractId = UUID.randomUUID();

        metadataRepository.save(ContractMetadata.builder()
                .contractId(contractId)
                .contractType("NDA")
                .amount(1000.0)
                .build());

        analysisRepository.save(ContractAnalysis.builder()
                .contractId(contractId)
                .riskScore(2.0)
                .recommendation("Standard terms.")
                .status("COMPLETED")
                .createdAt(LocalDateTime.now())
                .build());

        List<ContractAnalysisRepository.FinancialExposureProjection> rows =
                analysisRepository.getFinancialExposureData();

        assertThat(rows)
                .filteredOn(p -> p.getContractId().equals(contractId))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.getVendorName()).isNull();
                    assertThat(p.getFileName()).isNull();
                });
    }
}
