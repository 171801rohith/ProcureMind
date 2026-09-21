package com.procuremind.contract_service.controller;

import com.procuremind.contract_service.dto.ContractResponseDto;
import com.procuremind.contract_service.exception.UnsupportedFileTypeException;
import com.procuremind.contract_service.service.ContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('VIEWER','ANALYST','ADMIN')")
public class ContractController {
    private final ContractService contractService;

    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadContract(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "vendorName", defaultValue = "Unknown Vendor") String vendorName
    ) {
        try {
            ContractResponseDto response = contractService.processNewContract(file, vendorName);
            return ResponseEntity.accepted().body(response);
        } catch (UnsupportedFileTypeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Try again later.");
        }
    }

    @GetMapping
    public ResponseEntity<List<ContractResponseDto>> getAllContracts() {
        return ResponseEntity.ok(contractService.getAllContracts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContractResponseDto> getContractDetails(@PathVariable UUID id) {
        return contractService.getContractDetails(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<Map<String, String>> getContractStatus(@PathVariable UUID id) {
        return contractService.getContractStatus(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

}
