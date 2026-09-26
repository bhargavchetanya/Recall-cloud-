package dev.recall.service;

import dev.recall.config.RecallProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * A token bucket per user for POST /events: {@code eventsPerMinute} requests, refilled continuously. In memory, so it
 * is per backend instance, which is what a single-node team server needs.
 */
@Service
public class EventRateLimiter {

    /** Thrown when a user is over the limit; {@code retryAfter} is when the next request would be allowed. */
    public static class RateLimited extends RuntimeException {
        private final Duration retryAfter;

        public RateLimited(Duration retryAfter) {
            super("Too many event batches; retry in " + Math.max(1, retryAfter.toSeconds()) + " s.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long lastNanos) {
            this.tokens = tokens;
            this.lastNanos = lastNanos;
        }
    }

    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final double capacity;
    private final double tokensPerNano;
    private final Clock clock;

    public EventRateLimiter(RecallProperties properties, Clock clock) {
        this.capacity = properties.rateLimit().eventsPerMinute();
        this.tokensPerNano = capacity / Duration.ofMinutes(1).toNanos();
        this.clock = clock;
    }

    /** Takes one token for {@code userId} or throws {@link RateLimited}. */
    public void acquire(UUID userId) {
        long now = nowNanos();
        Bucket bucket = buckets.computeIfAbsent(userId, id -> new Bucket(capacity, now));
        synchronized (bucket) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastNanos) * tokensPerNano);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return;
            }
            long waitNanos = (long) Math.ceil((1 - bucket.tokens) / tokensPerNano);
            throw new RateLimited(Duration.ofNanos(waitNanos));
        }
    }

    private long nowNanos() {
        var i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }
}
