package io.openisoworld.net.ticket;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues single-use tickets that let a player open the WebSocket, and consumes them. Safe to call
 * from any thread.
 *
 * <p>A ticket carries the player's identity from the REST call that issued it to the WebSocket
 * handshake. It works once and only for {@link #TTL}, so it does no harm if it ever ends up in a
 * URL or a log. This class never logs a ticket.
 */
public final class TicketStore {

    public static final Duration TTL = Duration.ofSeconds(30);

    /** Tickets issued and not used yet. Caps the memory an endless stream of requests can take. */
    public static final int MAX_PENDING = 10_000;

    private static final int TICKET_BYTES = 32; // 256 random bits: impossible to guess

    private final Clock clock;
    private final int maxPending;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public TicketStore(Clock clock) {
        this(clock, MAX_PENDING);
    }

    TicketStore(Clock clock, int maxPending) {
        this.clock = clock;
        this.maxPending = maxPending;
    }

    /** Issues a ticket for a player. Empty when too many tickets are waiting to be used. */
    public Optional<String> issue(String playerId) {
        Instant now = clock.instant();
        if (pending.size() >= maxPending) {
            // Expired tickets stay until the map is full: only then is it worth sweeping them.
            pending.values().removeIf(ticket -> ticket.isExpired(now));
            if (pending.size() >= maxPending) {
                return Optional.empty();
            }
        }
        String ticket = newTicket();
        pending.put(ticket, new Pending(playerId, now.plus(TTL)));
        return Optional.of(ticket);
    }

    /** Returns the ticket's player the first time it is used, and never again; empty once expired. */
    public Optional<String> consume(String ticket) {
        // remove is atomic: of two handshakes with the same ticket, only one gets it.
        Pending found = pending.remove(ticket);
        if (found == null || found.isExpired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(found.playerId());
    }

    private String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes); // 43 characters, safe in a query string
    }

    private record Pending(String playerId, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
