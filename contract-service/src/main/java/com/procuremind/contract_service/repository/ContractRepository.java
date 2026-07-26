package com.procuremind.contract_service.repository;

import com.procuremind.contract_service.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContractRepository extends JpaRepository<Contract, UUID> {
}
