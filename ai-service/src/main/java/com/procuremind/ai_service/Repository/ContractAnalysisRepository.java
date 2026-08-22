package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.ContractAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractAnalysisRepository extends JpaRepository<ContractAnalysis, UUID> {

    interface FinancialExposureProjection {
        UUID getContractId();

        String getContractType();

        Double getAmount();

        Double getRiskScore();

        Long getHighRiskCount();
    }

    boolean existsByContractId(UUID contractId);

    Optional<ContractAnalysis> findByContractId(UUID contractId);

    @Query("SELECT COUNT(c) FROM ContractAnalysis c WHERE c.riskScore >= 6.0")
    long countHighRiskContracts();

    @Query("SELECT AVG(c.riskScore) FROM ContractAnalysis c")
    Double getAverageRiskScore();

    List<ContractAnalysis> findByContractIdIn(List<UUID> contractIds);


    @Query("""
            SELECT a.contractId AS contractId,
                   m.contractType AS contractType,
                   m.amount AS amount,
                   a.riskScore AS riskScore,
                   (SELECT COUNT(r) FROM AnalysisRisk r WHERE r.analysis = a AND r.severity = 'HIGH') AS highRiskCount
            FROM  ContractAnalysis a
            JOIN ContractMetadata m ON m.contractId = a.contractId
            WHERE m.amount IS NOT NULL
            """)
    List<FinancialExposureProjection> getFinancialExposureData();
}
