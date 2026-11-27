package io.openisoworld.app.ticket;

import io.openisoworld.net.ticket.TicketStore;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Issues the tickets that open the WebSocket. Part of the REST API, not of the message protocol. */
@RestController
class TicketController {

    private final TicketStore tickets;

    TicketController(TicketStore tickets) {
        this.tickets = tickets;
    }

    /**
     * A ticket for a new anonymous guest, who gets a random player id. The WebSocket accepts it
     * once, within 30 seconds. 503 when too many tickets are waiting to be used.
     */
    @PostMapping("/api/tickets")
    ResponseEntity<TicketResponse> issue() {
        String playerId = UUID.randomUUID().toString();
        return tickets.issue(playerId)
                .map(ticket -> ResponseEntity.ok(new TicketResponse(ticket)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
    }

    record TicketResponse(String ticket) {}
}
