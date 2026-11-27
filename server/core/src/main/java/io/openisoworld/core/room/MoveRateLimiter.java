package io.openisoworld.core.room;

/**
 * Allows about five moves per second per player, measured in room ticks.
 *
 * <p>A token bucket: it starts full with {@link #CAPACITY} tokens, each move takes one, and one
 * token comes back every {@link #TICKS_PER_TOKEN} ticks. At about 10 ticks a second, a player can
 * click five times in a row, then about five times a second.
 */
final class MoveRateLimiter {

    static final int CAPACITY = 5;
    static final int TICKS_PER_TOKEN = 2;

    private int tokens = CAPACITY;
    private long lastRefill; // the tick up to which tokens have been counted

    MoveRateLimiter(long tick) {
        this.lastRefill = tick;
    }

    /** Takes a token if one is available at this tick. */
    boolean tryAcquire(long tick) {
        refill(tick);
        if (tokens == 0) {
            return false;
        }
        tokens--;
        return true;
    }

    private void refill(long tick) {
        long earned = (tick - lastRefill) / TICKS_PER_TOKEN;
        if (tokens + earned >= CAPACITY) {
            // A full bucket does not save time for later: counting starts again from now.
            tokens = CAPACITY;
            lastRefill = tick;
        } else {
            // Move forward only by the ticks that earned a token, so no part of a token is lost.
            tokens += (int) earned;
            lastRefill += earned * TICKS_PER_TOKEN;
        }
    }
}
