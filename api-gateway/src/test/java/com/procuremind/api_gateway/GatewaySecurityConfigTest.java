package com.procuremind.api_gateway;

import static com.procuremind.api_gateway.SecurityOutcomeAssertions.assertSecurityAllows;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Phase 7/8 - the gateway enforces by default (note the deliberate absence of any
 * app.security.enforce override) and applies the role matrix from plan section 12.
 *
 * <p>Only the security outcome is asserted: 401 means unauthenticated, 403 means
 * authenticated with the wrong role. An allowed request reaches the proxy, where the
 * absent downstream service makes it fail; see {@link SecurityOutcomeAssertions}.
 */
@SpringBootTest(properties = {
        // Point every downstream at a closed port so the only thing these tests can observe
        // is the gateway's own decision. Without this, a locally running contract-service
        // answers the proxied call with its own 401 and the assertions become environment
        // dependent.
        "CONTRACT_SERVICE_URL=http://localhost:1",
        "AI_SERVICE_URL=http://localhost:1",
        "AUTH_SERVICE_URL=http://localhost:1"
})
@AutoConfigureMockMvc
class GatewaySecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void publicEndpointsStayOpenToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/api")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestsToProtectedRoutesAreRejected() throws Exception {
        mockMvc.perform(get("/api/contracts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analysis/dashboard-metrics")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/contracts/upload")).andExpect(status().isUnauthorized());
        mockMvc.perform(jsonPost("/api/analysis/chat")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} may read {1}")
    @CsvSource({
            "VIEWER,/api/contracts",
            "VIEWER,/api/analysis/dashboard-metrics",
            "ANALYST,/api/contracts",
            "ADMIN,/api/contracts"
    })
    void everyRoleMayReadTheReadModels(String role, String path) throws Exception {
        assertSecurityAllows(mockMvc, get(path).with(as(role)));
    }

    @ParameterizedTest(name = "VIEWER is forbidden from POST {0}")
    @CsvSource({
            "/api/contracts/upload",
            "/api/analysis/chat"
    })
    void viewerMayNotIngestOrChat(String path) throws Exception {
        mockMvc.perform(jsonPost(path).with(as("VIEWER"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} may POST {1}")
    @CsvSource({
            "ANALYST,/api/contracts/upload",
            "ANALYST,/api/analysis/chat",
            "ADMIN,/api/contracts/upload",
            "ADMIN,/api/analysis/chat"
    })
    void analystAndAdminMayIngestAndChat(String role, String path) throws Exception {
        assertSecurityAllows(mockMvc, jsonPost(path).with(as(role)));
    }

    @Test
    void userAdministrationIsAdminOnly() throws Exception {
        // Backs the React admin dashboard. auth-service enforces the same rule again, so
        // this is defence in depth rather than the only check.
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users").with(as("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").with(as("ANALYST"))).andExpect(status().isForbidden());
        mockMvc.perform(jsonPost("/api/users").with(as("ANALYST"))).andExpect(status().isForbidden());
        assertSecurityAllows(mockMvc, get("/api/users").with(as("ADMIN")));
    }

    @Test
    void actuatorDetailIsAdminOnly() throws Exception {
        mockMvc.perform(get("/actuator/info").with(as("VIEWER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").with(as("ANALYST"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/info").with(as("ADMIN")))
                .andExpect(status().is(not(401)))
                .andExpect(status().is(not(403)));
    }

    @Test
    void noXUserHeaderIsTrustedAsAuthentication() throws Exception {
        // A forged identity header must not authenticate anything; only a validated JWT does.
        mockMvc.perform(get("/api/contracts")
                        .header("X-User-Id", "attacker")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsPreflightIsAllowedAnonymouslyAndUsesTheExplicitAllowList() throws Exception {
        mockMvc.perform(options("/api/contracts")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Origin", not("*")));
    }

    private static MockHttpServletRequestBuilder jsonPost(String path) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
    }
}
