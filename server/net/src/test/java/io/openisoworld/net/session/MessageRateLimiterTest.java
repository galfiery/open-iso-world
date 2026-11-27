package io.openisoworld.net.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MessageRateLimiterTest {

    private long now = 1_000; // nanoseconds, moved by hand
    private final MessageRateLimiter limiter = new MessageRateLimiter(() -> now);

    private void drain() {
        for (int i = 0; i < MessageRateLimiter.CAPACITY; i++) {
            assertTrue(limiter.tryAcquire(), "message " + (i + 1));
        }
    }

    private void advance(Duration duration) {
        now += duration.toNanos();
    }

    @Test
    void allowsABurstOfTwentyThenRefuses() {
        drain();

        assertFalse(limiter.tryAcquire());
    }

    @Test
    void givesAMessageBackEveryFiftyMilliseconds() {
        drain();

        advance(Duration.ofMillis(49));
        assertFalse(limiter.tryAcquire());
        advance(Duration.ofMillis(1));
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void refillsCompletelyInOneSecond() {
        drain();

        advance(Duration.ofSeconds(1));

        drain();
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void doesNotSaveSilenceForLater() {
        advance(Duration.ofMinutes(1));

        drain();
        assertFalse(limiter.tryAcquire());
    }
}
