package com.procuremind.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.procuremind.auth.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the hosted login page and form-login flow backed by the JPA user store and the
 * bootstrap admin seeded by {@code DataSeeder} (see Phase 1, section 23).
 */
@AutoConfigureMockMvc
class LoginPageTest extends AbstractPostgresIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void loginPageIsPublicAndRendersAForm() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")));
    }

    @Test
    void validCredentialsAuthenticate() throws Exception {
        mockMvc.perform(formLogin().user(ADMIN_USERNAME).password(ADMIN_PASSWORD))
                .andExpect(authenticated().withUsername(ADMIN_USERNAME).withRoles("ADMIN"));
    }

    @Test
    void badCredentialsAreRejected() throws Exception {
        mockMvc.perform(formLogin().user(ADMIN_USERNAME).password("wrong-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
