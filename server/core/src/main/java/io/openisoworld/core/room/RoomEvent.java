package io.openisoworld.core.room;

import io.openisoworld.core.grid.Tile;
import java.util.List;

/**
 * Something that happened in a room during a tick. The transport turns each event into a protocol
 * message: {@code RoomSnapshot} into {@code roomState}, {@code PlayerJoined} into
 * {@code playerJoined}, {@code PlayerLeft} into {@code playerLeft}, {@code PathStarted} into
 * {@code path} and {@code MoveRejected} into {@code error}.
 */
public sealed interface RoomEvent {

    /** The whole room, for a player who just entered. */
    record RoomSnapshot(String roomId, int width, int height, List<Tile> blocked, List<PlayerView> players)
            implements RoomEvent {
        public RoomSnapshot {
            blocked = List.copyOf(blocked);
            players = List.copyOf(players);
        }
    }

    /** A player entered the room. */
    record PlayerJoined(PlayerView player) implements RoomEvent {}

    /** A player left the room. */
    record PlayerLeft(String playerId) implements RoomEvent {}

    /** A player starts walking. The first tile is where the walk starts, the last is the goal. */
    record PathStarted(String playerId, List<Tile> tiles) implements RoomEvent {
        public PathStarted {
            tiles = List.copyOf(tiles);
        }
    }

    /** A move was refused. {@code seq} is the number of the client message that asked for it. */
    record MoveRejected(String playerId, Reason reason, int seq) implements RoomEvent {

        /** Why the move was refused. The protocol reports all three as {@code invalidTile}. */
        public enum Reason {
            OUTSIDE_ROOM,
            BLOCKED,
            UNREACHABLE
        }
    }
}
