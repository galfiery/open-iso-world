package io.openisoworld.core.room;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoveRateLimiterTest {

    private final MoveRateLimiter limiter = new MoveRateLimiter(0);

    @Test
    void allowsABurstOfFiveThenRefuses() {
        drain(0);

        assertFalse(limiter.tryAcquire(0));
    }

    @Test
    void givesATokenBackEveryTwoTicks() {
        drain(0);

        assertFalse(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(2));
        assertFalse(limiter.tryAcquire(2));
    }

    @Test
    void keepsTheTickThatDidNotEarnAToken() {
        drain(0);

        assertFalse(limiter.tryAcquire(1));
        assertTrue(limiter.tryAcquire(3)); // earned at tick 2
        assertTrue(limiter.tryAcquire(4)); // earned at tick 4, not at tick 5
    }

    @Test
    void refillsUpToFiveAndNoMore() {
        drain(0);

        drain(1_000);
        assertFalse(limiter.tryAcquire(1_000));
    }

    @Test
    void aFullBucketDoesNotSaveTime() {
        drain(1); // the bucket was full, and unused, since tick 0

        assertFalse(limiter.tryAcquire(2));
        assertTrue(limiter.tryAcquire(3));
    }

    @Test
    void allowsFiveMovesASecondForever() {
        drain(0);

        // At 10 ticks a second, one move every two ticks is five a second.
        for (long tick = 2; tick <= 60; tick += MoveRateLimiter.TICKS_PER_TOKEN) {
            assertTrue(limiter.tryAcquire(tick), "Refused at tick " + tick);
        }
    }

    /** Takes all the tokens of a full bucket at this tick. */
    private void drain(long tick) {
        for (int i = 0; i < MoveRateLimiter.CAPACITY; i++) {
            assertTrue(limiter.tryAcquire(tick), "Refused move " + (i + 1));
        }
    }
}
