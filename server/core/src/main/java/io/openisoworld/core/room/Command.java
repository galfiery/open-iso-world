package io.openisoworld.core.room;

import io.openisoworld.core.grid.Tile;

/** An intention sent to a room. The room validates and applies it during its next tick. */
public sealed interface Command {

    /** A player enters the room. The id comes from the transport and must be unique in the room. */
    record Join(String playerId) implements Command {}

    /** A player leaves the room. */
    record Leave(String playerId) implements Command {}

    /**
     * A player asks to walk to a tile. {@code seq} is the number of the client's message: the room
     * does not read it, it only puts it back in a refusal.
     */
    record MoveTo(String playerId, Tile target, int seq) implements Command {}
}
