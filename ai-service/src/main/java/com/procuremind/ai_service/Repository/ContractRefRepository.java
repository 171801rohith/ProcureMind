package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.ContractRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Read-only access to contract-service's {@code contracts} table (see {@link ContractRef}).
 *
 * <p>No ai-service code path may call {@code save}/{@code saveAll}/{@code delete}/
 * {@code deleteAll} on this repository — contract-service is the sole writer of this table
 * per the architecture review (finding #16). This interface only exists to support read joins
 * (e.g. {@code ContractAnalysisRepository#getFinancialExposureData()}).
 */
@Repository
public interface ContractRefRepository extends JpaRepository<ContractRef, UUID> {
}
