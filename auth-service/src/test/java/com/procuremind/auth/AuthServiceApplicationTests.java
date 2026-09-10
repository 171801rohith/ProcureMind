package com.procuremind.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.procuremind.auth.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the auth-service context stands up against a real PostgreSQL instance with all
 * Flyway migrations applied, that the JDBC-backed authorization-server beans and the custom
 * JWK source are wired in, and that the OIDC discovery / JWKS endpoints serve the expected
 * issuer and key (see Phase 1, sections 10 and 23).
 */
@AutoConfigureMockMvc
class AuthServiceApplicationTests extends AbstractPostgresIntegrationTest {

    @Autowired
    RegisteredClientRepository registeredClientRepository;

    @Autowired
    OAuth2AuthorizationService authorizationService;

    @Autowired
    OAuth2AuthorizationConsentService authorizationConsentService;

    @Autowired
    JWKSource<SecurityContext> jwkSource;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    AuthorizationServerSettings authorizationServerSettings;

    @Autowired
    MockMvc mockMvc;

    @Test
    void contextLoads() {
        assertThat(registeredClientRepository).isNotNull();
        assertThat(authorizationService).isNotNull();
        assertThat(authorizationConsentService).isNotNull();
        assertThat(jwkSource).isNotNull();
        assertThat(jwtDecoder).isNotNull();
    }

    @Test
    void issuerIsTheStaticBrowserFacingUrl() {
        assertThat(authorizationServerSettings.getIssuer()).isEqualTo("http://localhost:8083");
    }

    @Test
    void discoveryDocumentAdvertisesTheStaticIssuerAndCoreEndpoints() throws Exception {
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer", is("http://localhost:8083")))
                .andExpect(jsonPath("$.authorization_endpoint", is("http://localhost:8083/oauth2/authorize")))
                .andExpect(jsonPath("$.token_endpoint", is("http://localhost:8083/oauth2/token")))
                .andExpect(jsonPath("$.jwks_uri", is("http://localhost:8083/oauth2/jwks")))
                .andExpect(jsonPath("$.userinfo_endpoint", is("http://localhost:8083/userinfo")));
    }

    @Test
    void jwksEndpointServesTheConfiguredSigningKey() throws Exception {
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty", is("RSA")))
                .andExpect(jsonPath("$.keys[0].kid", is("test-signing-key")))
                .andExpect(jsonPath("$.keys[0].use", is("sig")))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }
}
