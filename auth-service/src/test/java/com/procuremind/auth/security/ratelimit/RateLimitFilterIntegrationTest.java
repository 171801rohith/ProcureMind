package com.procuremind.auth.security.ratelimit;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.auth.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end coverage for {@link RateLimitFilter} on the real {@code /login} and
 * {@code /oauth2/token} endpoints, wired through the actual {@code DefaultSecurityConfig} /
 * {@code AuthorizationServerConfig} filter chains against a real Postgres-backed context (see
 * {@code ARCHITECTURE_REVIEW.md} finding #5).
 *
 * <p>Capacities are lowered via {@code @DynamicPropertySource} so the limit can be reached
 * deterministically in a handful of requests without waiting out a real refill window. Every
 * test uses its own unique IP and/or username(s) so the singleton limiter beans (shared across
 * this test class's single Spring context) never bleed state between test methods regardless
 * of execution order.
 */
@AutoConfigureMockMvc
class RateLimitFilterIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final int IP_CAPACITY = 10;
    private static final int IDENTITY_CAPACITY = 3;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void rateLimitProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.rate-limit.ip.capacity", () -> IP_CAPACITY);
        registry.add("auth.rate-limit.ip.period", () -> "PT1M");
        registry.add("auth.rate-limit.identity.capacity", () -> IDENTITY_CAPACITY);
        registry.add("auth.rate-limit.identity.period", () -> "PT1M");
    }

    @Test
    void fourthLoginAttemptForSameUsernameIsRejectedWith429() throws Exception {
        String ip = "10.20.0.1";
        String username = "rate-limit-user-a";

        for (int i = 0; i < IDENTITY_CAPACITY; i++) {
            mockMvc.perform(loginRequest(username, ip)).andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(loginRequest(username, ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("Too many attempts")))
                // Generic rejection: never reveals which identity or IP tripped the limit.
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString(username))));
    }

    @Test
    void differentUsernamesFromTheSameIpDoNotShareAnIdentityBudget() throws Exception {
        String ip = "10.20.0.2";

        // Each username is attempted once; well under its own identity budget, so all succeed
        // even though several distinct identities share the same source IP.
        for (int i = 0; i < IDENTITY_CAPACITY + 1; i++) {
            mockMvc.perform(loginRequest("rate-limit-user-b" + i, ip))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Test
    void eleventhLoginFromSameIpAcrossDistinctUsernamesIsRejectedByTheIpLimit() throws Exception {
        String ip = "10.20.0.3";

        // A fresh username per request keeps every identity budget untouched; only the shared
        // IP budget is exercised here.
        for (int i = 0; i < IP_CAPACITY; i++) {
            mockMvc.perform(loginRequest("rate-limit-user-c" + i, ip))
                    .andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(loginRequest("rate-limit-user-c" + IP_CAPACITY, ip))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void sameUsernameFromDifferentIpsSharesOneIdentityBudgetSoIpRotationCannotBypassIt() throws Exception {
        String username = "rate-limit-user-d";

        for (int i = 0; i < IDENTITY_CAPACITY; i++) {
            mockMvc.perform(loginRequest(username, "10.20.0.4" + i)).andExpect(status().is3xxRedirection());
        }

        // A brand new source IP does not grant a fresh per-username budget: brute-forcing one
        // account by rotating IPs is still caught.
        mockMvc.perform(loginRequest(username, "10.20.0.99"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void legitimateTrafficUnderTheLimitIsUnaffected() throws Exception {
        String ip = "10.20.0.5";
        String username = "rate-limit-user-e";

        for (int i = 0; i < IDENTITY_CAPACITY; i++) {
            // 3xx (redirect to /login?error for the deliberately wrong password) confirms the
            // request reached Spring Security at all, i.e. it was not rejected with 429.
            mockMvc.perform(loginRequest(username, ip))
                    .andExpect(status().is3xxRedirection());
        }
    }

    @Test
    void fourthTokenRequestForSameClientIdIsRejectedWith429() throws Exception {
        String ip = "10.20.0.6";
        // Seeded public client (see DataSeeder); no client secret is needed for its
        // authentication method (NONE), only the client_id parameter.
        String clientId = "procuremind-react";

        for (int i = 0; i < IDENTITY_CAPACITY; i++) {
            mockMvc.perform(tokenRequest(clientId, ip)).andExpect(status().is4xxClientError());
        }

        mockMvc.perform(tokenRequest(clientId, ip)).andExpect(status().isTooManyRequests());
    }

    private MockHttpServletRequestBuilder loginRequest(String username, String ip) {
        return post("/login")
                .param("username", username)
                .param("password", "wrong-password")
                .with(csrf())
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    private MockHttpServletRequestBuilder tokenRequest(String clientId, String ip) {
        // Deliberately a bogus refresh token: the point is to exercise the rate limiter ahead
        // of client/grant validation, not to obtain a real token.
        return post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", "not-a-real-refresh-token")
                .param("client_id", clientId)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }
}
