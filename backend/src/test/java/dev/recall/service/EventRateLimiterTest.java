package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.recall.ManualClock;
import dev.recall.config.RecallProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventRateLimiterTest {

    private final ManualClock clock = new ManualClock();
    private final EventRateLimiter limiter = new EventRateLimiter(
            new RecallProperties(null, null, null, null, null, null, null, new RecallProperties.RateLimit(100)), clock);
    private final UUID ada = UUID.randomUUID();

    @Test
    void allowsTheConfiguredNumberPerMinuteThenRefuses() {
        for (int i = 0; i < 100; i++) {
            limiter.acquire(ada);
        }
        assertThatThrownBy(() -> limiter.acquire(ada))
                .isInstanceOf(EventRateLimiter.RateLimited.class)
                .satisfies(e -> assertThat(((EventRateLimiter.RateLimited) e).retryAfter())
                        .isEqualTo(Duration.ofMillis(600)))
                .hasMessage("Too many event batches; retry in 1 s.");
    }

    @Test
    void refillsContinuously() {
        for (int i = 0; i < 100; i++) {
            limiter.acquire(ada);
        }
        clock.now = clock.now.plusMillis(600);
        limiter.acquire(ada);
        assertThatThrownBy(() -> limiter.acquire(ada)).isInstanceOf(EventRateLimiter.RateLimited.class);

        clock.now = clock.now.plus(Duration.ofMinutes(5));
        for (int i = 0; i < 100; i++) {
            limiter.acquire(ada);
        }
        assertThatThrownBy(() -> limiter.acquire(ada)).as("the bucket never holds more than its capacity")
                .isInstanceOf(EventRateLimiter.RateLimited.class);
    }

    @Test
    void usersHaveSeparateBuckets() {
        for (int i = 0; i < 100; i++) {
            limiter.acquire(ada);
        }
        limiter.acquire(UUID.randomUUID());
    }
}
