package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.ContractAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractAnalysisRepository extends JpaRepository<ContractAnalysis, UUID> {
    boolean existsByContractId(UUID contractId);

    Optional<ContractAnalysis> findByContractId(UUID contractId);
}
