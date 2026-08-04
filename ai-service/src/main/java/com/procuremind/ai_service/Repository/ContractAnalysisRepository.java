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
    boolean existsByContractId(UUID contractId);

    Optional<ContractAnalysis> findByContractId(UUID contractId);

    @Query("SELECT COUNT(c) FROM ContractAnalysis c WHERE c.riskScore >= 7.0")
    long countHighRiskContracts();

    @Query("SELECT AVG(c.riskScore) FROM ContractAnalysis c")
    double getAverageRiskScore();

    List<ContractAnalysis> findByContractIdIn(List<UUID> contractIds);
}
