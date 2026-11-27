package io.openisoworld.core.room;

import io.openisoworld.core.grid.Grid;
import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Command.Join;
import io.openisoworld.core.room.Command.Leave;
import io.openisoworld.core.room.Recipients.Everyone;
import io.openisoworld.core.room.Recipients.EveryoneExcept;
import io.openisoworld.core.room.Recipients.OnlyPlayer;
import io.openisoworld.core.room.RoomEvent.PlayerJoined;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The simulation of one room: its grid and the players in it.
 *
 * <p>A room has no threads and no clock. Each call to {@link #tick} applies the commands received
 * since the last tick and returns what happened. Only one thread at a time may call it, so the
 * state needs no locks.
 */
public final class Room {

    private final String id;
    private final Grid grid;
    private final Tile spawn;
    private final Map<String, PlayerState> players = new LinkedHashMap<>(); // in order of entry

    /** Creates an empty room. Players who enter appear on {@code spawn}, which must be walkable. */
    public Room(String id, Grid grid, Tile spawn) {
        this.id = id;
        this.grid = grid;
        this.spawn = spawn;
        if (!grid.isWalkable(spawn)) {
            throw new IllegalArgumentException("The spawn tile " + spawn + " is not walkable");
        }
    }

    /** Applies the commands received since the last tick and returns this tick's events, in order. */
    public List<Outgoing> tick(List<Command> commands) {
        List<Outgoing> events = new ArrayList<>();
        for (Command command : commands) {
            switch (command) {
                case Join join -> join(join.playerId(), events);
                case Leave leave -> leave(leave.playerId(), events);
            }
        }
        return events;
    }

    private void join(String playerId, List<Outgoing> events) {
        if (players.containsKey(playerId)) {
            return; // A bug in the transport, not something the client did: ignore it.
        }
        PlayerState player = new PlayerState(playerId, spawn);
        players.put(playerId, player);
        events.add(new Outgoing(new OnlyPlayer(playerId), snapshot()));
        if (players.size() > 1) { // Nobody else to tell when the room was empty.
            events.add(new Outgoing(new EveryoneExcept(playerId), new PlayerJoined(player.view())));
        }
    }

    private void leave(String playerId, List<Outgoing> events) {
        if (players.remove(playerId) != null && !players.isEmpty()) { // Nobody left to tell otherwise.
            events.add(new Outgoing(new Everyone(), new PlayerLeft(playerId)));
        }
    }

    private RoomSnapshot snapshot() {
        List<PlayerView> views = players.values().stream().map(PlayerState::view).toList();
        return new RoomSnapshot(id, grid.width(), grid.height(), grid.blockedTiles(), views);
    }

    /** What the room knows about a player. Changed only by the tick. */
    private static final class PlayerState {
        private final String id;
        private final Tile position;

        PlayerState(String id, Tile position) {
            this.id = id;
            this.position = position;
        }

        PlayerView view() {
            return new PlayerView(id, position, List.of());
        }
    }
}
