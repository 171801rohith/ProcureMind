package com.procuremind.ai_service.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.ai_service.controller.AnalysisController;
import com.procuremind.ai_service.controller.ChatController;
import com.procuremind.ai_service.dto.ChatDtos;
import com.procuremind.ai_service.service.AnalysisQueryService;
import com.procuremind.ai_service.service.ConversationService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
@WebMvcTest(properties = "app.security.enforce=false",
        controllers = { AnalysisController.class, ChatController.class })
@Import({ SecurityConfig.class, MethodSecurityConfig.class, JwtDecoderConfig.class })
class AiServiceSecurityKillSwitchTest {

    @MockitoBean
    AnalysisQueryService analysisQueryService;

    @MockitoBean
    ConversationService conversationService;

    @Autowired
    MockMvc mockMvc;

    @Test
    void anonymousReadsAreRestored() throws Exception {
        mockMvc.perform(get("/api/analysis/dashboard-metrics"))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)))
                .andExpect(unauthenticated());
    }

    @Test
    void theAnonymousChatEndpointIsReachableAgain() throws Exception {
        BDDMockito.given(conversationService.handleChat(ArgumentMatchers.any()))
                .willReturn(new ChatDtos.ChatResponseDto("hello", java.util.List.of(), java.util.List.of()));

        // Chat carries the strictest @PreAuthorize in this service, so it is the clearest
        // proof that method security is genuinely off rather than merely bypassed by path.
        mockMvc.perform(chatRequest())
                .andExpect(status().isOk())
                .andExpect(unauthenticated());
    }

    @Test
    void aPresentedTokenIsStillValidatedAndPopulatesTheSecurityContext() throws Exception {
        mockMvc.perform(get("/api/analysis/dashboard-metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)))
                .andExpect(authenticated());
    }

    @Test
    void aTokenWithoutAnyRoleIsAcceptedWhileTheSwitchIsOff() throws Exception {
        // With method security disabled there is no role to check, so a roleless token is
        // treated like an anonymous caller rather than producing a 403.
        mockMvc.perform(get("/api/analysis/dashboard-metrics").with(jwt()))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)))
                .andExpect(authenticated());
    }

    @Test
    void aMalformedBearerTokenIsStillRejected() throws Exception {
        // The kill switch relaxes authorization, never token validation: a caller that
        // presents a token still has to present a real one.
        mockMvc.perform(get("/api/analysis/dashboard-metrics").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder chatRequest() {
        return post("/api/analysis/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userMessage\":\"hi\",\"conversationId\":\"c1\"}");
    }
}
