package com.procuremind.contract_service.service;

import com.procuremind.contract_service.dto.ContractResponseDto;
import com.procuremind.contract_service.entity.Contract;
import com.procuremind.contract_service.repository.ContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

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

        return ContractResponseDto.builder()
                .id(contract.getId())
                .fileName(contract.getFilename())
                .vendorName(contract.getVendorName())
                .status(contract.getStatus())
                .uploadedAt(contract.getUploadedAt())
                .build();
    }

}
