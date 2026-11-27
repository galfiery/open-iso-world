package io.openisoworld.core.room;

import java.util.Objects;

/** An event leaving the room, with the players who receive it. */
public record Outgoing(Recipients to, RoomEvent event) {
    public Outgoing {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(event, "event");
    }
}
