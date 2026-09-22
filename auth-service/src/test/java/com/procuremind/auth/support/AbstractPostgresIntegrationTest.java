package com.procuremind.auth.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for auth-service integration tests. Boots the full application context against
 * a PostgreSQL container so Flyway migrations run exactly as they would in production, and
 * supplies the secret-bearing properties the {@code DataSeeder} needs
 * (see {@code docs/AUTH_IMPLEMENTATION_PLAN.md} section 23).
 *
 * <p>The container uses the shared-singleton pattern (started once in a static initializer,
 * reaped by Testcontainers' Ryuk at JVM exit) rather than {@code @Testcontainers} /
 * {@code @Container}: several test classes share this base and Spring's context cache, so a
 * per-class container lifecycle would leave a cached context pointing at a stopped
 * container.
 *
 * <p>The {@code dev} profile is active here because these tests run with no JWK keystore
 * configured (see {@code auth.jwk.keystore-path} below); {@link com.procuremind.auth.config.JwkKeyConfig}
 * fails fast on a missing keystore outside that profile.
 */
@SpringBootTest
@ActiveProfiles("dev")
public abstract class AbstractPostgresIntegrationTest {

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "test-admin-password";
    protected static final String STREAMLIT_SECRET = "test-streamlit-secret";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("procuremind_auth")
                    .withUsername("auth")
                    .withPassword("auth");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("auth.admin.username", () -> ADMIN_USERNAME);
        registry.add("auth.admin.password", () -> ADMIN_PASSWORD);
        registry.add("auth.clients.streamlit.client-secret", () -> STREAMLIT_SECRET);
        registry.add("auth.jwk.keystore-path", () -> "");
        registry.add("auth.jwk.key-id", () -> "test-signing-key");
    }
}
