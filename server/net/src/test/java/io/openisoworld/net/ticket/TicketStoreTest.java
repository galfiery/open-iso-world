package io.openisoworld.net.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TicketStoreTest {

    private final TestClock clock = new TestClock();
    private final TicketStore tickets = new TicketStore(clock);

    @Test
    void givesThePlayerBackTheFirstTime() {
        String ticket = tickets.issue("p-1").orElseThrow();

        assertEquals(Optional.of("p-1"), tickets.consume(ticket));
    }

    @Test
    void worksOnlyOnce() {
        String ticket = tickets.issue("p-1").orElseThrow();
        tickets.consume(ticket);

        assertEquals(Optional.empty(), tickets.consume(ticket));
    }

    @Test
    void refusesAnUnknownTicket() {
        assertEquals(Optional.empty(), tickets.consume("not-a-ticket"));
    }

    @Test
    void worksUntilItExpires() {
        String ticket = tickets.issue("p-1").orElseThrow();

        clock.advance(TicketStore.TTL.minusSeconds(1));

        assertEquals(Optional.of("p-1"), tickets.consume(ticket));
    }

    @Test
    void expiresAfterThirtySeconds() {
        String ticket = tickets.issue("p-1").orElseThrow();

        clock.advance(TicketStore.TTL);

        assertEquals(Optional.empty(), tickets.consume(ticket));
    }

    @Test
    void givesEachRequestItsOwnTicket() {
        String first = tickets.issue("p-1").orElseThrow();
        String second = tickets.issue("p-1").orElseThrow();

        assertNotEquals(first, second);
        assertEquals(Optional.of("p-1"), tickets.consume(first));
        assertEquals(Optional.of("p-1"), tickets.consume(second));
    }

    @Test
    void writesTicketsInTheUrlSafeAlphabet() {
        String ticket = tickets.issue("p-1").orElseThrow();

        assertTrue(ticket.matches("[A-Za-z0-9_-]{43}"), ticket);
    }

    @Test
    void refusesWhenTooManyTicketsArePending() {
        var small = new TicketStore(clock, 2);
        small.issue("p-1");
        small.issue("p-2");

        assertEquals(Optional.empty(), small.issue("p-3"));
    }

    @Test
    void makesRoomByDroppingExpiredTickets() {
        var small = new TicketStore(clock, 2);
        String old = small.issue("p-1").orElseThrow();
        small.issue("p-2");
        clock.advance(TicketStore.TTL);

        assertTrue(small.issue("p-3").isPresent());
        assertEquals(Optional.empty(), small.consume(old));
    }

    @Test
    void usedTicketsFreeTheirPlace() {
        var small = new TicketStore(clock, 1);
        small.consume(small.issue("p-1").orElseThrow());

        assertTrue(small.issue("p-2").isPresent());
    }

    @Test
    void givesTheTicketToOnlyOneOfTwoHandshakesAtOnce() throws Exception {
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 200; round++) {
                String ticket = tickets.issue("p-1").orElseThrow();
                var start = new CountDownLatch(1);
                Future<Optional<String>> first = threads.submit(() -> consumeAfter(start, ticket));
                Future<Optional<String>> second = threads.submit(() -> consumeAfter(start, ticket));
                start.countDown();

                int winners = (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0);
                assertEquals(1, winners, "round " + round);
            }
        }
    }

    private Optional<String> consumeAfter(CountDownLatch start, String ticket) throws InterruptedException {
        start.await();
        return tickets.consume(ticket);
    }

    /** A clock that only moves when the test says so. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-27T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
