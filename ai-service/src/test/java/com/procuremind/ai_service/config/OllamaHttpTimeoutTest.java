package com.procuremind.ai_service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.http.client.HttpClientProperties;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Proves finding #2 (revised): a hung Ollama call must fail within a bounded time instead of
 * blocking the Kafka consumer thread indefinitely.
 *
 * <p>Two things have to be true together, and each is proven separately here:
 * <ol>
 *   <li>{@code spring.http.client.read-timeout} actually bounds the HTTP call. This is the
 *       same {@link RestClient.Builder} bean that Spring AI's {@code OpenAiChatAutoConfiguration}
 *       consumes via {@code restClientBuilderProvider.getIfAvailable(RestClient::builder)}, so
 *       proving the auto-configured builder honours the property proves the production wiring
 *       does too, without needing to also stand up the OpenAI chat model, tool-calling and
 *       datasource/Kafka machinery just to make one HTTP call.</li>
 *   <li>Spring AI's own retry layer ({@code spring.ai.retry.max-attempts}) does not silently
 *       multiply that timeout. Its default retries a timed-out call ({@link
 *       ResourceAccessException}) up to 10 times with exponential backoff — left at that
 *       default, one hung call would cost up to ~10x the read-timeout instead of being bounded
 *       by it, which would defeat the point of setting the read-timeout at all.</li>
 * </ol>
 */
class OllamaHttpTimeoutTest {

    @Test
    void restClientHonorsTheConfiguredReadTimeoutInsteadOfHangingIndefinitely() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread neverResponds = new Thread(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Thread.sleep(30_000);
                } catch (Exception ignored) {
                    // Test teardown or the expected client-side timeout; either way, done.
                }
            });
            neverResponds.setDaemon(true);
            neverResponds.start();

            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            SslAutoConfiguration.class,
                            HttpClientAutoConfiguration.class,
                            RestClientAutoConfiguration.class))
                    .withPropertyValues(
                            "spring.http.client.connect-timeout=2s",
                            "spring.http.client.read-timeout=2s")
                    .run(context -> {
                        RestClient.Builder builder = context.getBean(RestClient.Builder.class);
                        RestClient client = builder.baseUrl("http://localhost:" + port).build();

                        long start = System.nanoTime();
                        assertThatThrownBy(() -> client.get().retrieve().body(String.class))
                                .isInstanceOf(ResourceAccessException.class);
                        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

                        // Comfortably above the 2s timeout (scheduling slack) but nowhere near
                        // what an unbounded hang against a server that never responds would be.
                        assertThat(elapsedMs).isBetween(1_900L, 10_000L);
                    });
        }
    }

    @Test
    void theShippedAiServiceConfigSetsAnEighteenMinuteReadTimeout() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(HttpClientPropertiesHolder.class)
                .run(context -> {
                    HttpClientProperties properties = context.getBean(HttpClientProperties.class);
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofMinutes(18));
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
                });
    }

    @Test
    void theShippedAiServiceConfigCapsSpringAisOwnRetryAtOneAttempt() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
                .run(context -> {
                    SpringAiRetryProperties properties = context.getBean(SpringAiRetryProperties.class);
                    assertThat(properties.getMaxAttempts())
                            .as("a hung call must fail once, not be retried up to 10x by Spring AI on top "
                                    + "of the read-timeout above")
                            .isEqualTo(1);
                });
    }

    @Configuration
    @EnableConfigurationProperties(HttpClientProperties.class)
    static class HttpClientPropertiesHolder {
    }
}
