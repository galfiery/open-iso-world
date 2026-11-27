package io.openisoworld.core.room;

/** An intention sent to a room. The room validates and applies it during its next tick. */
public sealed interface Command {

    /** A player enters the room. The id comes from the transport and must be unique in the room. */
    record Join(String playerId) implements Command {}

    /** A player leaves the room. */
    record Leave(String playerId) implements Command {}
}
