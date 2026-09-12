package com.procuremind.ai_service.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.ai_service.controller.AnalysisController;
import com.procuremind.ai_service.controller.ChatController;
import com.procuremind.ai_service.service.AnalysisQueryService;
import com.procuremind.ai_service.service.ConversationService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 7/8 - ai-service enforces by default and validates the JWT itself, so a caller that
 * bypasses the gateway and hits port 8082 directly gets the same treatment.
 *
 * <p>A web slice, so it needs no Postgres, Kafka, MinIO or LLM. Only deny outcomes are
 * asserted for chat: the allow side of the matrix is covered at the gateway.
 */
@WebMvcTest(controllers = { AnalysisController.class, ChatController.class })
@Import({ SecurityConfig.class, MethodSecurityConfig.class, JwtDecoderConfig.class })
class AiServiceSecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AnalysisQueryService analysisQueryService;

    @MockitoBean
    ConversationService conversationService;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void anonymousApiRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/analysis/dashboard-metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(chatRequest()).andExpect(status().isUnauthorized());
    }

    @Test
    void noXUserHeaderIsTrustedAsAuthentication() throws Exception {
        mockMvc.perform(get("/api/analysis/dashboard-metrics")
                        .header("X-User-Id", "attacker")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anyAuthenticatedRoleMayReadTheAnalysisReadModels() throws Exception {
        mockMvc.perform(get("/api/analysis/dashboard-metrics").with(as("VIEWER")))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)))
                .andExpect(authenticated());
    }

    @Test
    void viewerMayNotUseTheChatAssistant() throws Exception {
        // @PreAuthorize on ChatController.chat, backed by @EnableMethodSecurity. A 403 here
        // also proves the LLM-backed controller body was never invoked.
        mockMvc.perform(chatRequest().with(as("VIEWER"))).andExpect(status().isForbidden());
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

    private static MockHttpServletRequestBuilder chatRequest() {
        return post("/api/analysis/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userMessage\":\"hi\",\"conversationId\":\"c1\"}");
    }
}
