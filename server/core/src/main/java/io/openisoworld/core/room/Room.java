package io.openisoworld.core.room;

import io.openisoworld.core.grid.Grid;
import io.openisoworld.core.grid.Pathfinder;
import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Command.Join;
import io.openisoworld.core.room.Command.Leave;
import io.openisoworld.core.room.Command.MoveTo;
import io.openisoworld.core.room.Recipients.Everyone;
import io.openisoworld.core.room.Recipients.EveryoneExcept;
import io.openisoworld.core.room.Recipients.OnlyPlayer;
import io.openisoworld.core.room.RoomEvent.MoveRejected;
import io.openisoworld.core.room.RoomEvent.MoveRejected.Reason;
import io.openisoworld.core.room.RoomEvent.PathStarted;
import io.openisoworld.core.room.RoomEvent.PlayerJoined;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The simulation of one room: its grid and the players in it.
 *
 * <p>A room has no threads and no clock. Each call to {@link #tick} first moves the walking
 * players, then applies the commands received since the last tick, and returns what happened.
 * Only one thread at a time may call it, so the state needs no locks.
 */
public final class Room {

    /** Ticks per step: at about 10 ticks a second, players walk 2 tiles a second, diagonals included. */
    public static final int STEP_TICKS = 5;

    private final String id;
    private final Grid grid;
    private final Tile spawn;
    private final Map<String, PlayerState> players = new LinkedHashMap<>(); // in order of entry
    private long currentTick; // counts the ticks: the room's clock

    /** Creates an empty room. Players who enter appear on {@code spawn}, which must be walkable. */
    public Room(String id, Grid grid, Tile spawn) {
        this.id = id;
        this.grid = grid;
        this.spawn = spawn;
        if (!grid.isWalkable(spawn)) {
            throw new IllegalArgumentException("The spawn tile " + spawn + " is not walkable");
        }
    }

    public String id() {
        return id;
    }

    /**
     * Moves the walking players, applies the commands received since the last tick and returns
     * this tick's events, in order.
     */
    public List<Outgoing> tick(List<Command> commands) {
        currentTick++;
        // Steps produce no events: clients are already animating the paths they received.
        players.values().forEach(PlayerState::advance);

        List<Outgoing> events = new ArrayList<>();
        for (Command command : commands) {
            switch (command) {
                case Join join -> join(join.playerId(), events);
                case Leave leave -> leave(leave.playerId(), events);
                case MoveTo move -> moveTo(move, events);
            }
        }
        return events;
    }

    private void join(String playerId, List<Outgoing> events) {
        if (players.containsKey(playerId)) {
            return; // A bug in the transport, not something the client did: ignore it.
        }
        PlayerState player = new PlayerState(playerId, spawn, currentTick);
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

    private void moveTo(MoveTo move, List<Outgoing> events) {
        PlayerState player = players.get(move.playerId());
        if (player == null) {
            return; // The player left in this same tick.
        }
        // Before any other check: even a move to a wrong tile costs a token, and a path search.
        if (!player.moves.tryAcquire(currentTick)) {
            events.add(rejection(move, Reason.RATE_LIMITED));
            return;
        }
        Tile target = move.target();
        if (!grid.contains(target)) {
            events.add(rejection(move, Reason.OUTSIDE_ROOM));
            return;
        }
        if (!grid.isWalkable(target)) {
            events.add(rejection(move, Reason.BLOCKED));
            return;
        }
        if (!player.isWalking() && target.equals(player.position)) {
            return; // Already standing there.
        }
        Optional<List<Tile>> path = Pathfinder.find(grid, player.walkStart(), target);
        if (path.isEmpty()) {
            events.add(rejection(move, Reason.UNREACHABLE));
            return;
        }
        player.follow(path.get());
        events.add(new Outgoing(new Everyone(), new PathStarted(player.id, path.get())));
    }

    private static Outgoing rejection(MoveTo move, Reason reason) {
        var refused = new MoveRejected(move.playerId(), reason, move.seq());
        return new Outgoing(new OnlyPlayer(move.playerId()), refused);
    }

    private RoomSnapshot snapshot() {
        List<PlayerView> views = players.values().stream().map(PlayerState::view).toList();
        return new RoomSnapshot(id, grid.width(), grid.height(), grid.blockedTiles(), views);
    }

    /** What the room knows about a player. Changed only by the tick. */
    private static final class PlayerState {
        private final String id;
        private Tile position; // the last tile reached
        private final Deque<Tile> path = new ArrayDeque<>(); // the tiles still to walk; empty when standing
        private int ticksToNextStep;
        private final MoveRateLimiter moves;

        PlayerState(String id, Tile position, long tick) {
            this.id = id;
            this.position = position;
            this.moves = new MoveRateLimiter(tick);
        }

        boolean isWalking() {
            return !path.isEmpty();
        }

        /**
         * Where a new path starts. A walking player first finishes the step in progress, so the
         * path starts from the tile they are walking to, and nobody jumps back.
         */
        Tile walkStart() {
            return isWalking() ? path.getFirst() : position;
        }

        /** Follows a path that starts at {@link #walkStart()}. */
        void follow(List<Tile> tiles) {
            boolean walking = isWalking();
            path.clear();
            if (walking) {
                path.addAll(tiles); // The first tile is the one the step in progress reaches.
            } else {
                path.addAll(tiles.subList(1, tiles.size())); // The first tile is the current position.
                ticksToNextStep = STEP_TICKS;
            }
        }

        /** One tick of walking: after STEP_TICKS ticks the player reaches the next tile. */
        void advance() {
            if (!isWalking()) {
                return;
            }
            ticksToNextStep--;
            if (ticksToNextStep == 0) {
                position = path.removeFirst();
                ticksToNextStep = STEP_TICKS;
            }
        }

        PlayerView view() {
            return new PlayerView(id, position, List.copyOf(path));
        }
    }
}
