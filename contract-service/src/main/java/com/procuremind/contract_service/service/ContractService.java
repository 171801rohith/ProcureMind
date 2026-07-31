package com.procuremind.contract_service.service;

import com.procuremind.contract_service.dto.ContractResponseDto;
import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import com.procuremind.contract_service.service.kafka.ContractEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractService {
    private final StorageService storageService;
    private final ContractRepository contractRepository;
    private final ContractEventProducer contractEventProducer;

    @Transactional
    public ContractResponseDto processNewContract(MultipartFile file, String vendorName) throws Exception {
        String objName = storageService.uploadFile(file);

        Contract contract = Contract.builder()
                .filename(file.getOriginalFilename())
                .minioObjectName(objName)
                .vendorName(vendorName)
                .status("UPLOADED")
                .uploadedAt(LocalDateTime.now())
                .build();

        contract = contractRepository.save(contract);

        contractEventProducer.publishContractUploadEvent(
                contract.getId(),
                contract.getFilename(),
                contract.getMinioObjectName()
        );

        return mapTo(contract);
    }

    @Transactional(readOnly = true)
    public List<ContractResponseDto> getAllContracts() {
           log.info("Fetching All contracts");
           return contractRepository.findAll().stream()
                   .map(this::mapTo)
                   .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<ContractResponseDto> getContractDetails(UUID contractId) {
        log.info("Fetching details for contract: {}", contractId);
        return  contractRepository.findById(contractId)
                .map(this::mapTo);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, String>> getContractStatus(UUID contractId) {
        log.info("Fetching status for contract: {}", contractId);
        return  contractRepository.findById(contractId)
                .map(contract -> Map.of("status", contract.getStatus()));
    }

    private ContractResponseDto mapTo(Contract contract) {
        return ContractResponseDto.builder()
                .id(contract.getId())
                .fileName(contract.getFilename())
                .vendorName(contract.getVendorName())
                .status(contract.getStatus())
                .uploadedAt(contract.getUploadedAt())
                .build();
    }

}
