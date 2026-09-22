package com.procuremind.auth.bootstrap;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.procuremind.auth.config.AuthProperties;
import com.procuremind.auth.entity.Role;
import com.procuremind.auth.entity.User;
import com.procuremind.auth.repository.RoleRepository;
import com.procuremind.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Idempotent bootstrap that runs on every start (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md}
 * sections 10 and 13):
 *
 * <ul>
 *   <li>the bootstrap {@code ADMIN} user, when {@code auth.admin.password} is supplied;</li>
 *   <li>the public {@code procuremind-react} client (Authorization Code + PKCE);</li>
 *   <li>the confidential {@code procuremind-streamlit} client, when
 *       {@code auth.clients.streamlit.client-secret} is supplied.</li>
 * </ul>
 *
 * Roles themselves are created by Flyway ({@code V2__seed_roles.sql}); this class only
 * reads them. Nothing here is created twice: each step checks for an existing row first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final AuthProperties properties;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedAdminUser();
        seedReactClient();
        seedStreamlitClient();
    }

    private void seedAdminUser() {
        String username = properties.getAdmin().getUsername();
        String password = properties.getAdmin().getPassword();

        if (!StringUtils.hasText(password)) {
            log.warn("auth.admin.password (AUTH_ADMIN_PASSWORD) is not set; skipping bootstrap admin user seed");
            return;
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            log.info("Bootstrap admin user '{}' already exists; leaving it unchanged", username);
            return;
        }
        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing; V2__seed_roles.sql did not run"));

        User admin = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .enabled(true)
                .roles(new HashSet<>(Set.of(adminRole)))
                .build();
        userRepository.save(admin);
        log.info("Seeded bootstrap admin user '{}' with role ADMIN", username);
    }

    private void seedReactClient() {
        AuthProperties.Client cfg = properties.getClients().getReact();
        String clientId = cfg.getClientId();
        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        if (existing != null) {
            reconcileReactRedirectUris(existing, cfg);
            return;
        }
        RegisteredClient client = reactClient(UUID.randomUUID().toString(), cfg);
        registeredClientRepository.save(client);
        log.info("Registered public OAuth2 client '{}' (Authorization Code + PKCE)", clientId);
    }

    /**
     * Adds the silent-renew redirect URI to an already-registered SPA client.
     *
     * <p>The seeder otherwise leaves existing registrations alone, but this one URI has to
     * catch up: an environment seeded before silent renew existed would reject every
     * prompt=none renewal with {@code invalid_request}, and the only alternative would be
     * editing the {@code oauth2_registered_client} row by hand.
     */
    private void reconcileReactRedirectUris(RegisteredClient existing, AuthProperties.Client cfg) {
        String silentRedirectUri = cfg.getSilentRedirectUri();
        if (!StringUtils.hasText(silentRedirectUri) || existing.getRedirectUris().contains(silentRedirectUri)) {
            log.info("OAuth2 client '{}' already registered; leaving it unchanged", cfg.getClientId());
            return;
        }
        registeredClientRepository.save(reactClient(existing.getId(), cfg));
        log.info("Added silent-renew redirect URI to OAuth2 client '{}'", cfg.getClientId());
    }

    private RegisteredClient reactClient(String id, AuthProperties.Client cfg) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(cfg.getClientId())
                .clientName("ProcureMind React SPA")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(cfg.getRedirectUri())
                .postLogoutRedirectUri(cfg.getPostLogoutRedirectUri())
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("roles")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(tokenSettings());

        if (StringUtils.hasText(cfg.getSilentRedirectUri())) {
            builder.redirectUri(cfg.getSilentRedirectUri());
        }
        return builder.build();
    }

    private void seedStreamlitClient() {
        AuthProperties.Client cfg = properties.getClients().getStreamlit();
        String clientId = cfg.getClientId();
        String secret = cfg.getClientSecret();

        if (!StringUtils.hasText(secret)) {
            log.warn("auth.clients.streamlit.client-secret (STREAMLIT_OIDC_CLIENT_SECRET) is not set; "
                    + "skipping confidential client '{}' registration", clientId);
            return;
        }
        if (registeredClientRepository.findByClientId(clientId) != null) {
            log.info("OAuth2 client '{}' already registered; leaving it unchanged", clientId);
            return;
        }
        RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientName("ProcureMind Streamlit UI")
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(cfg.getRedirectUri())
                .postLogoutRedirectUri(cfg.getPostLogoutRedirectUri())
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("roles")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(tokenSettings())
                .build();
        registeredClientRepository.save(client);
        log.info("Registered confidential OAuth2 client '{}' (Authorization Code + PKCE + client secret)", clientId);
    }

    private TokenSettings tokenSettings() {
        Duration accessTtl = properties.getToken().getAccessTokenTtl();
        Duration refreshTtl = properties.getToken().getRefreshTokenTtl();
        return TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .accessTokenTimeToLive(accessTtl)
                .refreshTokenTimeToLive(refreshTtl)
                .reuseRefreshTokens(false)
                .build();
    }
}
