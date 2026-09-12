package com.procuremind.ai_service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The JWKS URL is the one setting that genuinely differs between running in Docker Compose
 * and running from an IDE, because {@code auth-service} is a Compose DNS name that does not
 * resolve on the host. Getting it wrong is silent: the service starts happily and then
 * rejects every valid token with a 401.
 *
 * <p>The convention asserted here is that the checked-in default is the host/IDE value and
 * Compose supplies the container value through {@code AUTH_JWK_SET_URI}. The issuer is
 * deliberately independent of it, so the {@code iss} claim is validated identically in both
 * modes.
 */
class JwkSetUriConfigurationTest {

    private static final String JWK_SET_URI = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";
    private static final String ISSUER_URI = "app.security.issuer-uri";

    @Test
    void theDefaultTargetsLocalhostSoAnIdeRunWorksWithoutEditingConfig() throws IOException {
        MockEnvironment environment = applicationYaml();

        assertThat(environment.getProperty(JWK_SET_URI)).isEqualTo("http://localhost:8083/oauth2/jwks");
    }

    @Test
    void composeOverridesTheHostWithTheServiceName() throws IOException {
        MockEnvironment environment = applicationYaml();
        // Exactly what docker-compose.yaml injects for this service.
        environment.getPropertySources().addFirst(new MapPropertySource("compose",
                Map.of("AUTH_JWK_SET_URI", "http://auth-service:8083/oauth2/jwks")));

        assertThat(environment.getProperty(JWK_SET_URI)).isEqualTo("http://auth-service:8083/oauth2/jwks");
    }

    @Test
    void theIssuerStaysTheStaticBrowserFacingValueInBothModes() throws IOException {
        MockEnvironment environment = applicationYaml();
        assertThat(environment.getProperty(ISSUER_URI)).isEqualTo("http://localhost:8083");

        environment.getPropertySources().addFirst(new MapPropertySource("compose",
                Map.of("AUTH_JWK_SET_URI", "http://auth-service:8083/oauth2/jwks")));

        // Moving the key lookup onto the Docker network must not move the expected issuer,
        // or tokens minted for browsers would fail validation inside the cluster.
        assertThat(environment.getProperty(ISSUER_URI)).isEqualTo("http://localhost:8083");
    }

    @Test
    void composeActuallySuppliesTheServiceNameToEveryResourceServer() throws IOException {
        Path compose = Path.of("..", "docker-compose.yaml");
        Assumptions.assumeTrue(Files.exists(compose), "docker-compose.yaml not resolvable from this working directory");

        String contents = Files.readString(compose);
        long overrides = contents.lines()
                .filter(line -> line.contains("AUTH_JWK_SET_URI:"))
                .peek(line -> assertThat(line).contains("http://auth-service:8083/oauth2/jwks"))
                .count();

        // api-gateway, contract-service and ai-service each validate tokens independently.
        assertThat(overrides).isEqualTo(3);
    }

    private static MockEnvironment applicationYaml() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }
}
