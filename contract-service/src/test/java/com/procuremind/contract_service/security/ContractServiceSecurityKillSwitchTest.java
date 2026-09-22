package com.procuremind.contract_service.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * {@code AUTH_ENABLED=false} is the emergency kill switch, and it now genuinely restores
 * anonymous access.
 *
 * <p>Both halves of the authorization stack follow the one property: the permissive filter
 * chain replaces the enforcing one, and {@code MethodSecurityConfig} is not registered, so
 * the controllers' {@code @PreAuthorize} rules are inert. Previously only the chain was
 * switched and method security kept rejecting anonymous callers.
 *
 * <p>{@code MethodSecurityConfig} is imported here on purpose: the switch has to be what
 * disables method security, not the test quietly leaving it out.
 */
@WebMvcTest(properties = "app.security.enforce=false", controllers = ContractController.class)
@Import({ SecurityConfig.class, MethodSecurityConfig.class, JwtDecoderConfig.class })
class ContractServiceSecurityKillSwitchTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ContractService contractService;

    @Test
    void anonymousReadsAreRestored() throws Exception {
        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(unauthenticated());
    }

    @Test
    void anonymousWritesAreRestoredToo() throws Exception {
        // Upload carries the strictest @PreAuthorize in this service, so it is the clearest
        // proof that method security is genuinely off rather than merely bypassed by path.
        mockMvc.perform(uploadRequest())
                .andExpect(status().isAccepted())
                .andExpect(unauthenticated());
    }

    @Test
    void aPresentedTokenIsStillValidatedAndPopulatesTheSecurityContext() throws Exception {
        mockMvc.perform(get("/api/contracts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ANALYST"))))
                .andExpect(status().isOk())
                .andExpect(authenticated());
    }

    @Test
    void aTokenWithoutAnyRoleIsAcceptedWhileTheSwitchIsOff() throws Exception {
        // With method security disabled there is no role to check, so a roleless token is
        // treated like an anonymous caller rather than producing a 403.
        mockMvc.perform(get("/api/contracts").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(authenticated());
    }

    @Test
    void aMalformedBearerTokenIsStillRejected() throws Exception {
        // The kill switch relaxes authorization, never token validation: a caller that
        // presents a token still has to present a real one.
        mockMvc.perform(get("/api/contracts").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private static MockMultipartHttpServletRequestBuilder uploadRequest() {
        return multipart("/api/contracts/upload")
                .file(new MockMultipartFile("file", "contract.pdf", "application/pdf", "pdf".getBytes()))
                .param("vendorName", "Acme");
    }
}
