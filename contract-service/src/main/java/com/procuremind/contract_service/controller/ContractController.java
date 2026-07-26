package com.procuremind.contract_service.controller;

import com.procuremind.contract_service.dto.ContractResponseDto;
import com.procuremind.contract_service.service.ContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractController {
    private final ContractService contractService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadContract(
            @RequestPart("file")MultipartFile file,
            @RequestParam(value = "vendorName", defaultValue = "Unknown Vendor") String vendorName
            ) {
            try {
                ContractResponseDto response = contractService.processNewContract(file, vendorName);
                return ResponseEntity.accepted().body(response);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body("Try again later.");
            }
    }

}
