package com.procuremind.api_gateway;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;

/**
 * Shared assertion for the gateway security slice tests.
 *
 * <p>No downstream service runs in these tests, so a request the security chain admits
 * blows up inside the gateway proxy with a connection error. Reaching the proxy at all is
 * the proof that neither the authentication nor the authorization filter rejected it, so
 * that specific failure is treated as success and anything else is rethrown.
 */
final class SecurityOutcomeAssertions {

    private SecurityOutcomeAssertions() {
    }

    static void assertSecurityAllows(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
        try {
            mockMvc.perform(request)
                    .andExpect(status().is(not(401)))
                    .andExpect(status().is(not(403)));
        } catch (Exception e) {
            if (!downstreamUnreachable(e)) {
                throw e;
            }
        }
    }

    private static boolean downstreamUnreachable(Throwable thrown) {
        for (Throwable cause = thrown; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof ResourceAccessException) {
                return true;
            }
        }
        return false;
    }
}
