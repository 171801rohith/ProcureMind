package com.procuremind.ai_service.Repository;

import com.procuremind.ai_service.entity.ContractMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ContractMetadataRepository extends JpaRepository<ContractMetadata, UUID> {
}
