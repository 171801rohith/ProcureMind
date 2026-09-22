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

    @Autowired
    com.procuremind.auth.bootstrap.DataSeeder dataSeeder;

    @Test
    void reactClientIsPublicWithPkce() {
        RegisteredClient client = registeredClients.findByClientId("procuremind-react");

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        // The refresh_token grant is registered, but Spring Authorization Server still will
        // not issue one: OAuth2RefreshTokenGenerator returns null for the authorization_code
        // grant whenever the client authenticates with `none`. The registration is kept so
        // the client can be made confidential later without a migration, and renewal happens
        // through prompt=none silent renew in the meantime.
        assertThat(client.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isFalse();
        assertThat(client.getScopes()).contains("openid", "profile", "roles");
        assertThat(client.getRedirectUris()).contains("http://localhost:5173/");
        // Silent renew is how this public client gets a fresh access token, and Spring
        // Authorization Server rejects any redirect_uri it does not know, so the iframe
        // target has to be registered too.
        assertThat(client.getRedirectUris()).contains("http://localhost:5173/silent-renew.html");
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

    @Test
    void anExistingSpaRegistrationGainsTheSilentRenewRedirectUri() {
        RegisteredClient seeded = registeredClients.findByClientId("procuremind-react");

        // Reproduce an environment registered before silent renew existed: same client id
        // and same row id, but only the main redirect URI.
        registeredClients.save(RegisteredClient.from(seeded)
                .redirectUris(uris -> uris.removeIf(uri -> uri.endsWith("/silent-renew.html")))
                .build());
        assertThat(registeredClients.findByClientId("procuremind-react").getRedirectUris())
                .doesNotContain("http://localhost:5173/silent-renew.html");

        dataSeeder.run(null);

        // Without this reconciliation every prompt=none renewal would fail with
        // invalid_request, and the only other fix would be editing the table by hand.
        RegisteredClient reconciled = registeredClients.findByClientId("procuremind-react");
        assertThat(reconciled.getRedirectUris())
                .contains("http://localhost:5173/", "http://localhost:5173/silent-renew.html");
        assertThat(reconciled.getId()).isEqualTo(seeded.getId());
        assertThat(reconciled.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(reconciled.getClientSettings().isRequireProofKey()).isTrue();
    }
}
