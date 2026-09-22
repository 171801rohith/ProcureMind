package com.procuremind.auth.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Fast, Spring-free unit tests for the Bucket4j/Caffeine limiter itself (see
 * {@code ARCHITECTURE_REVIEW.md} finding #5): no HTTP, no application context.
 */
class RateLimiterServiceTest {

    @Test
    void allowsUpToCapacityThenRejectsTheNextAttempt() {
        RateLimiterService limiter = new RateLimiterService(3, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("alice")).isTrue();
        assertThat(limiter.tryConsume("alice")).isTrue();
        assertThat(limiter.tryConsume("alice")).isTrue();
        // 4th attempt within the window is the N+1th and must be rejected.
        assertThat(limiter.tryConsume("alice")).isFalse();
        // Once denied, it stays denied for the rest of the window.
        assertThat(limiter.tryConsume("alice")).isFalse();
    }

    @Test
    void differentKeysHaveIndependentBudgets() {
        RateLimiterService limiter = new RateLimiterService(1, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("alice")).isTrue();
        // alice is now exhausted, but bob has never been charged and is unaffected.
        assertThat(limiter.tryConsume("alice")).isFalse();
        assertThat(limiter.tryConsume("bob")).isTrue();
        assertThat(limiter.tryConsume("bob")).isFalse();
    }

    @Test
    void refillsAfterTheWindowElapses() throws InterruptedException {
        RateLimiterService limiter = new RateLimiterService(1, Duration.ofMillis(200));

        assertThat(limiter.tryConsume("carol")).isTrue();
        assertThat(limiter.tryConsume("carol")).isFalse();

        Thread.sleep(300);

        assertThat(limiter.tryConsume("carol")).isTrue();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new RateLimiterService(0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositivePeriod() {
        assertThatThrownBy(() -> new RateLimiterService(5, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
