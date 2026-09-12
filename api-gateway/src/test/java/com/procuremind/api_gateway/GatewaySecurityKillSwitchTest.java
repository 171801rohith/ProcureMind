package com.procuremind.api_gateway;

import static com.procuremind.api_gateway.SecurityOutcomeAssertions.assertSecurityAllows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 8 keeps AUTH_ENABLED as a documented emergency kill switch. Setting it to false
 * restores anonymous access, but a bearer token that is present must still be a valid one:
 * the permissive chain parses tokens, it does not ignore them.
 */
@SpringBootTest(properties = {
        "app.security.enforce=false",
        // See GatewaySecurityConfigTest: closed ports keep the assertions about the
        // gateway's own decision independent of whatever is running locally.
        "CONTRACT_SERVICE_URL=http://localhost:1",
        "AI_SERVICE_URL=http://localhost:1",
        "AUTH_SERVICE_URL=http://localhost:1"
})
@AutoConfigureMockMvc
class GatewaySecurityKillSwitchTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void anonymousAccessIsRestored() throws Exception {
        assertSecurityAllows(mockMvc, get("/api/contracts"));
        assertSecurityAllows(mockMvc, get("/api/analysis/dashboard-metrics"));
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void aMalformedBearerTokenIsStillRejected() throws Exception {
        mockMvc.perform(get("/api/contracts").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
