package com.procuremind.auth.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.procuremind.auth.config.ApiSecurityConfig;
import com.procuremind.auth.dto.UserDtos;
import com.procuremind.auth.service.UserAdminService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The user-administration API backs the admin dashboard, so its authorization rules are the
 * real boundary: hiding the navigation entry in React is a convenience on top of these.
 *
 * <p>A web slice, so it needs neither Postgres nor Docker.
 */
@WebMvcTest(UserAdminController.class)
@Import({ ApiSecurityConfig.class, UserAdminExceptionHandler.class })
class UserAdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserAdminService userAdminService;

    /** Tokens are injected by the test post-processor, so the decoder is never exercised. */
    @MockitoBean
    JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void adminMayListUsers() throws Exception {
        given(userAdminService.listUsers()).willReturn(List.of(
                new UserDtos.UserResponse(UUID.randomUUID(), "admin", null, true, List.of("ADMIN"))));

        mockMvc.perform(get("/api/users").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("admin"))
                .andExpect(jsonPath("$[0].roles[0]").value("ADMIN"));
    }

    @Test
    void theUserListNeverExposesCredentialMaterial() throws Exception {
        given(userAdminService.listUsers()).willReturn(List.of(
                new UserDtos.UserResponse(UUID.randomUUID(), "admin", "a@example.com", true, List.of("ADMIN"))));

        mockMvc.perform(get("/api/users").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    void adminMayCreateAUserWithSelectedRoles() throws Exception {
        given(userAdminService.createUser(any())).willReturn(
                new UserDtos.UserResponse(UUID.randomUUID(), "analyst", null, true, List.of("ANALYST")));

        mockMvc.perform(createRequest("{\"username\":\"analyst\",\"password\":\"analyst-pw\",\"roles\":[\"ANALYST\"]}")
                        .with(as("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("analyst"))
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"));
    }

    @ParameterizedTest(name = "{0} may not administer users")
    @ValueSource(strings = { "VIEWER", "ANALYST" })
    void nonAdminRolesAreForbidden(String role) throws Exception {
        mockMvc.perform(get("/api/users").with(as(role))).andExpect(status().isForbidden());
        mockMvc.perform(createRequest("{\"username\":\"x\",\"password\":\"password1\"}").with(as(role)))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).listUsers();
        verify(userAdminService, never()).createUser(any());
    }

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(createRequest("{\"username\":\"x\",\"password\":\"password1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noXUserHeaderIsTrustedAsAuthentication() throws Exception {
        mockMvc.perform(get("/api/users")
                        .header("X-User-Id", "attacker")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDuplicateUsernameIsReportedAsAConflict() throws Exception {
        willThrow(new IllegalArgumentException("Username already exists: admin"))
                .given(userAdminService).createUser(any());

        mockMvc.perform(createRequest("{\"username\":\"admin\",\"password\":\"password1\"}").with(as("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists: admin"));
    }

    @Test
    void anUnknownRoleIsReportedAsABadRequest() throws Exception {
        willThrow(new IllegalArgumentException("Unknown role: WIZARD"))
                .given(userAdminService).createUser(any());

        mockMvc.perform(createRequest("{\"username\":\"x\",\"password\":\"password1\",\"roles\":[\"WIZARD\"]}")
                        .with(as("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown role: WIZARD"));
    }

    @Test
    void aShortPasswordIsReportedPerFieldSoTheFormCanHighlightIt() throws Exception {
        mockMvc.perform(createRequest("{\"username\":\"x\",\"password\":\"short\"}").with(as("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(userAdminService, never()).createUser(any());
    }

    private static MockHttpServletRequestBuilder createRequest(String json) {
        return post("/api/users").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
