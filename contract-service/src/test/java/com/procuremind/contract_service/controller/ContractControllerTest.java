package com.procuremind.contract_service.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.contract_service.dto.ContractResponseDto;
import com.procuremind.contract_service.exception.UnsupportedFileTypeException;
import com.procuremind.contract_service.security.JwtDecoderConfig;
import com.procuremind.contract_service.security.MethodSecurityConfig;
import com.procuremind.contract_service.security.SecurityConfig;
import com.procuremind.contract_service.service.ContractService;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Exercises the actual request/response contract of {@code /api/contracts/upload}, as opposed
 * to {@link com.procuremind.contract_service.security.ContractServiceSecurityConfigTest} and
 * {@link com.procuremind.contract_service.security.ContractServiceSecurityKillSwitchTest},
 * which only assert role/authorization outcomes with {@link ContractService} stubbed to
 * whatever Mockito's default is. This class asserts the status code and body shape a real
 * caller sees for a success, a rejected-file-type failure, and an unexpected failure.
 */
@WebMvcTest(ContractController.class)
@Import({ SecurityConfig.class, MethodSecurityConfig.class, JwtDecoderConfig.class })
class ContractControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContractService contractService;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void successfulUploadReturns202WithTheContractResponseBody() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ContractResponseDto response = ContractResponseDto.builder()
                .id(id)
                .fileName("contract.pdf")
                .vendorName("Acme")
                .status("UPLOADED")
                .uploadedAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        given(contractService.processNewContract(any(), anyString())).willReturn(response);

        mockMvc.perform(uploadRequest().with(as("ANALYST")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.fileName").value("contract.pdf"))
                .andExpect(jsonPath("$.vendorName").value("Acme"))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void rejectedFileTypeReturns400WithTheSpecificValidationMessage() throws Exception {
        willThrow(new UnsupportedFileTypeException(
                "Unsupported file type 'image/png'; only PDF documents are accepted."))
                .given(contractService).processNewContract(any(), anyString());

        mockMvc.perform(uploadRequest().with(as("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Unsupported file type 'image/png'; only PDF documents are accepted."));
    }

    @Test
    void unexpectedServiceFailureFallsBackToTheGenericMessage() throws Exception {
        willThrow(new RuntimeException("MinIO unreachable"))
                .given(contractService).processNewContract(any(), anyString());

        mockMvc.perform(uploadRequest().with(as("ANALYST")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Try again later."));
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest() {
        return multipart("/api/contracts/upload")
                .file(new MockMultipartFile("file", "contract.pdf", "application/pdf", "pdf".getBytes()))
                .param("vendorName", "Acme");
    }
}
