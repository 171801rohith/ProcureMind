package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.ContractMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractMetadataRepository extends JpaRepository<ContractMetadata, UUID> {
    interface TypeDistributionProjection {
        String getContractType();

        Long getCount();
    }

    @Query("SELECT m.contractType AS contractType, COUNT(m) AS count FROM ContractMetadata m GROUP BY m.contractType")
    List<TypeDistributionProjection> getContractTypeDistribution();

    Optional<ContractMetadata> findByContractId(UUID contractId);

    List<ContractMetadata> findByContractIdIn(List<UUID> contractIds);
}
