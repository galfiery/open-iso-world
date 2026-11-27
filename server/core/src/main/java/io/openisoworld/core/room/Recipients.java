package io.openisoworld.core.room;

/** Who receives an event: deciding who sees what is a game rule, so the room decides it. */
public sealed interface Recipients {

    /** Every player in the room at the end of the tick. */
    record Everyone() implements Recipients {}

    /** One player only, for example the one whose command was refused. */
    record OnlyPlayer(String playerId) implements Recipients {}

    /** Every player in the room but one, for example everyone but the player who just entered. */
    record EveryoneExcept(String playerId) implements Recipients {}
}
