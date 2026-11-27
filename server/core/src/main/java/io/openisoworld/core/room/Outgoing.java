package io.openisoworld.core.room;

/** An event leaving the room, with the players who receive it. */
public record Outgoing(Recipients to, RoomEvent event) {}
