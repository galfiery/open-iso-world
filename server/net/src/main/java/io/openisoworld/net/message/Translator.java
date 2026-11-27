package io.openisoworld.net.message;

import io.openisoworld.core.room.Command;
import io.openisoworld.core.room.PlayerView;
import io.openisoworld.core.room.RoomEvent;
import io.openisoworld.core.room.RoomEvent.MoveRejected;
import io.openisoworld.core.room.RoomEvent.PathStarted;
import io.openisoworld.core.room.RoomEvent.PlayerJoined;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import io.openisoworld.net.protocol.ErrorCode;
import io.openisoworld.net.protocol.ErrorPayload;
import io.openisoworld.net.protocol.MoveToPayload;
import io.openisoworld.net.protocol.PathPayload;
import io.openisoworld.net.protocol.Player;
import io.openisoworld.net.protocol.PlayerJoinedPayload;
import io.openisoworld.net.protocol.PlayerLeftPayload;
import io.openisoworld.net.protocol.RoomStatePayload;
import io.openisoworld.net.protocol.ServerPayload;
import io.openisoworld.net.protocol.Tile;
import java.util.List;

/**
 * Translates between core types and protocol records: the only class that knows both, so the
 * protocol can change shape without touching the game logic. Both have a {@code Tile}; here the
 * protocol one is imported and the core one is written in full.
 */
public final class Translator {

    private Translator() {}

    /** The protocol payload of a room event. */
    public static ServerPayload toPayload(RoomEvent event) {
        // No default: a new event in core does not compile until it is translated here.
        return switch (event) {
            case RoomSnapshot room -> new RoomStatePayload(
                    room.roomId(), room.width(), room.height(), tiles(room.blocked()), players(room.players()));
            case PlayerJoined joined -> new PlayerJoinedPayload(player(joined.player()));
            case PlayerLeft left -> new PlayerLeftPayload(left.playerId());
            case PathStarted path -> new PathPayload(path.playerId(), tiles(path.tiles()));
            case MoveRejected refused -> error(refused);
        };
    }

    /** The room command for a client's moveTo. {@code seq} comes back in a refusal. */
    public static Command.MoveTo toCommand(String playerId, int seq, MoveToPayload move) {
        return new Command.MoveTo(playerId, new io.openisoworld.core.grid.Tile(move.x(), move.y()), seq);
    }

    private static ErrorPayload error(MoveRejected refused) {
        return switch (refused.reason()) {
            case OUTSIDE_ROOM -> new ErrorPayload(ErrorCode.INVALID_TILE, "The tile is outside the room", refused.seq());
            case BLOCKED -> new ErrorPayload(ErrorCode.INVALID_TILE, "The tile is blocked", refused.seq());
            case UNREACHABLE -> new ErrorPayload(ErrorCode.INVALID_TILE, "The tile cannot be reached", refused.seq());
            case RATE_LIMITED -> new ErrorPayload(
                    ErrorCode.RATE_LIMITED, "Too many moves: about 5 a second are allowed", refused.seq());
        };
    }

    private static Player player(PlayerView view) {
        // A player standing still has no path in the protocol, rather than an empty one.
        List<Tile> path = view.path().isEmpty() ? null : tiles(view.path());
        return new Player(view.playerId(), tile(view.position()), path);
    }

    private static List<Player> players(List<PlayerView> views) {
        return views.stream().map(Translator::player).toList();
    }

    private static List<Tile> tiles(List<io.openisoworld.core.grid.Tile> tiles) {
        return tiles.stream().map(Translator::tile).toList();
    }

    private static Tile tile(io.openisoworld.core.grid.Tile tile) {
        return new Tile(tile.x(), tile.y());
    }
}
