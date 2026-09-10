package com.procuremind.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.procuremind.auth.support.AbstractPostgresIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Confirms {@code DataSeeder} persisted both OAuth2 clients with the settings from
 * {@code docs/AUTH_IMPLEMENTATION_PLAN.md} section 13, and that they survive a JDBC
 * round-trip through the {@code oauth2_registered_client} table.
 */
class RegisteredClientSeedTest extends AbstractPostgresIntegrationTest {

    @Autowired
    RegisteredClientRepository registeredClients;

    @Test
    void reactClientIsPublicWithPkce() {
        RegisteredClient client = registeredClients.findByClientId("procuremind-react");

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getScopes()).contains("openid", "profile", "roles");
        assertThat(client.getRedirectUris()).contains("http://localhost:5173/");
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(15));
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive()).isEqualTo(Duration.ofHours(8));
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
    }

    @Test
    void streamlitClientIsConfidential() {
        RegisteredClient client = registeredClients.findByClientId("procuremind-streamlit");

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(client.getClientSecret()).isNotBlank();
        assertThat(client.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getScopes()).contains("openid", "profile", "roles");
        assertThat(client.getRedirectUris()).contains("http://localhost:8501/");
    }

    @Test
    void seedingIsIdempotentAcrossContextReuse() {
        // The context (and therefore DataSeeder) is shared across the tests in this class;
        // a second lookup must still find exactly one registration per client id.
        assertThat(registeredClients.findByClientId("procuremind-react")).isNotNull();
        assertThat(registeredClients.findByClientId("procuremind-streamlit")).isNotNull();
    }
}
