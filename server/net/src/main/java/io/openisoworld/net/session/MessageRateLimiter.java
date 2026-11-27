package io.openisoworld.net.session;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Allows about twenty messages a second per connection, in wall-clock time.
 *
 * <p>The same token bucket as the room's {@code MoveRateLimiter}, but counted in nanoseconds
 * rather than ticks, because the transport does not see the ticks: it starts full with
 * {@link #CAPACITY} tokens, each message takes one, and one comes back every {@link #REFILL}.
 */
final class MessageRateLimiter {

    static final int CAPACITY = 20;
    static final Duration REFILL = Duration.ofMillis(50);

    private static final long REFILL_NANOS = REFILL.toNanos();

    private final LongSupplier nanoTime;
    private int tokens = CAPACITY;
    private long lastRefill; // the time up to which tokens have been counted

    MessageRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastRefill = nanoTime.getAsLong();
    }

    /**
     * Takes a token if one is available. Synchronized because a session's messages arrive one at a
     * time, but not always on the same thread; the lock is never contended.
     */
    synchronized boolean tryAcquire() {
        refill(nanoTime.getAsLong());
        if (tokens == 0) {
            return false;
        }
        tokens--;
        return true;
    }

    private void refill(long now) {
        long earned = (now - lastRefill) / REFILL_NANOS;
        if (tokens + earned >= CAPACITY) {
            // A full bucket does not save time for later: counting starts again from now.
            tokens = CAPACITY;
            lastRefill = now;
        } else {
            // Move forward only by the time that earned a token, so no part of a token is lost.
            tokens += (int) earned;
            lastRefill += earned * REFILL_NANOS;
        }
    }
}
