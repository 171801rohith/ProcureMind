package com.procuremind.auth.security.ratelimit;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;

/**
 * A keyed, in-memory sliding-window limiter: each distinct key (e.g. {@code "ip:1.2.3.4"} or
 * {@code "user:alice"}) gets its own independent Bucket4j bucket, greedily refilled to a fixed
 * capacity every {@code period}. Buckets are held in a bounded Caffeine cache so identities
 * that stop being seen are evicted automatically instead of leaking memory forever — no
 * external store (Redis or otherwise) is involved, per the architecture decision that a
 * single auth-service instance doesn't warrant one.
 *
 * <p>Deliberately has no Spring dependency so it can be unit tested without a Spring context.
 */
public class RateLimiterService {

    // Defensive upper bound on distinct identities tracked at once; well beyond any realistic
    // login/token traffic for a single-instance auth-service, just here to prevent unbounded
    // growth if this were ever hit by a very large distributed identity-enumeration attempt.
    private static final long MAX_TRACKED_IDENTITIES = 100_000;

    private final int capacity;
    private final Duration period;
    private final Cache<String, Bucket> buckets;

    public RateLimiterService(int capacity, Duration period) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive, got " + period);
        }
        this.capacity = capacity;
        this.period = period;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_IDENTITIES)
                // A bucket that hasn't been touched for two full refill windows has nothing
                // left to remember; evicting it lets Caffeine reclaim the memory naturally.
                .expireAfterAccess(period.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Attempts to consume one token from the bucket for {@code key}, creating it on first use.
     *
     * @return {@code true} if the request is within the limit, {@code false} if it should be
     *         rejected.
     */
    public boolean tryConsume(String key) {
        Bucket bucket = buckets.get(key, k -> newBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, period)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
