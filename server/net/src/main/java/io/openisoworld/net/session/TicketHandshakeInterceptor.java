package io.openisoworld.net.session;

import io.openisoworld.net.ticket.TicketStore;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Lets the WebSocket open only with a valid ticket, and consumes it. From here on the player's
 * identity comes only from the ticket, never from a message.
 */
public final class TicketHandshakeInterceptor implements HandshakeInterceptor {

    /** The session attribute that holds the player id. */
    static final String PLAYER_ID = "playerId";

    private static final System.Logger LOG = System.getLogger(TicketHandshakeInterceptor.class.getName());

    private final TicketStore tickets;

    public TicketHandshakeInterceptor(TicketStore tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        Optional<String> playerId = ticket == null ? Optional.empty() : tickets.consume(ticket);
        if (playerId.isEmpty()) {
            // The same answer for a missing, unknown, expired or used ticket. Never log the ticket.
            LOG.log(System.Logger.Level.INFO, "Handshake refused: missing or invalid ticket");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(PLAYER_ID, playerId.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            @Nullable Exception exception) {}
}
