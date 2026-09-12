package com.procuremind.contract_service.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.contract_service.controller.ContractController;
import com.procuremind.contract_service.service.ContractService;

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
 * Phase 7/8 - contract-service enforces by default and validates the JWT itself, so a
 * caller that bypasses the gateway and hits port 8081 directly gets the same treatment.
 *
 * <p>A web slice, so it needs no Postgres, Kafka or MinIO. Only deny outcomes are asserted
 * for the write endpoint: letting an ANALYST through would exercise the mocked service
 * rather than the rule under test, and the allow side of the matrix is covered at the
 * gateway.
 */
@WebMvcTest(ContractController.class)
@Import({ SecurityConfig.class, MethodSecurityConfig.class, JwtDecoderConfig.class })
class ContractServiceSecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContractService contractService;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void anonymousApiRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/contracts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/contracts/00000000-0000-0000-0000-000000000000/status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(uploadRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void noXUserHeaderIsTrustedAsAuthentication() throws Exception {
        mockMvc.perform(get("/api/contracts")
                        .header("X-User-Id", "attacker")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anyAuthenticatedRoleMayReadContracts() throws Exception {
        mockMvc.perform(get("/api/contracts").with(as("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(authenticated());
    }

    @Test
    void viewerMayNotUpload() throws Exception {
        // @PreAuthorize on ContractController.uploadContract, backed by @EnableMethodSecurity.
        mockMvc.perform(uploadRequest().with(as("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test
    void actuatorIsAdminOnly() throws Exception {
        // Authorization runs in the filter chain, before dispatch, so the rule is visible
        // even though the actuator endpoints themselves are outside this slice.
        mockMvc.perform(get("/actuator/info").with(as("ANALYST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").with(as("ADMIN")))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void actuatorHealthStaysOpenForContainerProbes() throws Exception {
        // Not 401: the probe path is permitAll. The endpoint body is outside this slice.
        mockMvc.perform(get("/actuator/health")).andExpect(status().is(not(401)));
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest() {
        return multipart("/api/contracts/upload")
                .file(new MockMultipartFile("file", "contract.pdf", "application/pdf", "pdf".getBytes()))
                .param("vendorName", "Acme");
    }
}
