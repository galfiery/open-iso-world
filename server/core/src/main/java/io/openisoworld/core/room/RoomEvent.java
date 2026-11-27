package io.openisoworld.core.room;

import io.openisoworld.core.grid.Tile;
import java.util.List;
import java.util.Objects;

/**
 * Something that happened in a room during a tick. The transport turns each event into a protocol
 * message: {@code RoomSnapshot} into {@code roomState}, {@code PlayerJoined} into
 * {@code playerJoined}, {@code PlayerLeft} into {@code playerLeft}.
 */
public sealed interface RoomEvent {

    /** The whole room, for a player who just entered. */
    record RoomSnapshot(String roomId, int width, int height, List<Tile> blocked, List<PlayerView> players)
            implements RoomEvent {
        public RoomSnapshot {
            Objects.requireNonNull(roomId, "roomId");
            blocked = List.copyOf(blocked);
            players = List.copyOf(players);
        }
    }

    /** A player entered the room. */
    record PlayerJoined(PlayerView player) implements RoomEvent {
        public PlayerJoined {
            Objects.requireNonNull(player, "player");
        }
    }

    /** A player left the room. */
    record PlayerLeft(String playerId) implements RoomEvent {
        public PlayerLeft {
            Objects.requireNonNull(playerId, "playerId");
        }
    }
}
