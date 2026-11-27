package io.openisoworld.core.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.core.grid.Grid;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RoomTest {

    private static final Grid GRID = Grid.parse(
            "....",
            ".#..",
            "....");
    private static final Tile SPAWN = new Tile(0, 0);

    private final Room room = new Room("lobby", GRID, SPAWN);

    // Creating a room

    @Test
    void refusesABlockedSpawn() {
        assertThrows(IllegalArgumentException.class, () -> new Room("lobby", GRID, new Tile(1, 1)));
    }

    @Test
    void refusesASpawnOutsideTheGrid() {
        assertThrows(IllegalArgumentException.class, () -> new Room("lobby", GRID, new Tile(4, 0)));
    }

    @Test
    void aTickWithoutCommandsHasNoEvents() {
        assertTrue(room.tick(List.of()).isEmpty());
    }

    // Joining

    @Test
    void theFirstPlayerGetsTheWholeRoom() {
        List<Outgoing> events = room.tick(List.of(new Join("p-1")));

        var snapshot = new RoomSnapshot("lobby", 4, 3, List.of(new Tile(1, 1)), List.of(standing("p-1")));
        assertEquals(List.of(new Outgoing(new OnlyPlayer("p-1"), snapshot)), events);
    }

    @Test
    void aNewPlayerGetsEveryoneAndTheOthersGetTheNewPlayer() {
        room.tick(List.of(new Join("p-1")));

        List<Outgoing> events = room.tick(List.of(new Join("p-2")));

        var snapshot = new RoomSnapshot(
                "lobby", 4, 3, List.of(new Tile(1, 1)), List.of(standing("p-1"), standing("p-2")));
        assertEquals(
                List.of(
                        new Outgoing(new OnlyPlayer("p-2"), snapshot),
                        new Outgoing(new EveryoneExcept("p-2"), new PlayerJoined(standing("p-2")))),
                events);
    }

    @Test
    void joiningTwiceDoesNothing() {
        room.tick(List.of(new Join("p-1")));

        assertTrue(room.tick(List.of(new Join("p-1"))).isEmpty());
        assertEquals(List.of(standing("p-1")), playersSeenBy("p-2"));
    }

    // Leaving

    @Test
    void everyoneLearnsThatAPlayerLeft() {
        room.tick(List.of(new Join("p-1"), new Join("p-2")));

        List<Outgoing> events = room.tick(List.of(new Leave("p-1")));

        assertEquals(List.of(new Outgoing(new Everyone(), new PlayerLeft("p-1"))), events);
        assertEquals(List.of(standing("p-2")), playersSeenBy("p-3"));
    }

    @Test
    void theLastPlayerLeavesWithoutEvents() {
        room.tick(List.of(new Join("p-1")));

        assertTrue(room.tick(List.of(new Leave("p-1"))).isEmpty());
        assertEquals(List.of(), playersSeenBy("p-2"));
    }

    @Test
    void aPlayerWhoLeftCanJoinAgain() {
        room.tick(List.of(new Join("p-1")));
        room.tick(List.of(new Leave("p-1")));

        List<Outgoing> events = room.tick(List.of(new Join("p-1")));

        assertEquals(new OnlyPlayer("p-1"), events.getFirst().to());
    }

    @Test
    void leavingAnUnknownPlayerDoesNothing() {
        assertTrue(room.tick(List.of(new Leave("nobody"))).isEmpty());
    }

    @Test
    void eventsFollowTheOrderOfTheCommands() {
        room.tick(List.of(new Join("p-1")));

        List<Outgoing> events = room.tick(List.of(new Join("p-2"), new Leave("p-2")));

        assertEquals(3, events.size());
        assertEquals(new OnlyPlayer("p-2"), events.get(0).to());
        assertEquals(new PlayerJoined(standing("p-2")), events.get(1).event());
        assertEquals(new PlayerLeft("p-2"), events.get(2).event());
    }

    // Moving: refusals

    @Test
    void refusesATileOutsideTheRoom() {
        room.tick(List.of(new Join("p-1")));

        assertEquals(
                List.of(
                        new Outgoing(new OnlyPlayer("p-1"), new MoveRejected("p-1", Reason.OUTSIDE_ROOM, 7)),
                        new Outgoing(new OnlyPlayer("p-1"), new MoveRejected("p-1", Reason.OUTSIDE_ROOM, 8))),
                room.tick(List.of(new MoveTo("p-1", new Tile(4, 0), 7), new MoveTo("p-1", new Tile(0, 3), 8))));
        assertEquals(standing("p-1"), look().get("p-1"));
    }

    @Test
    void refusesABlockedTile() {
        room.tick(List.of(new Join("p-1")));

        assertEquals(
                List.of(new Outgoing(new OnlyPlayer("p-1"), new MoveRejected("p-1", Reason.BLOCKED, 3))),
                room.tick(List.of(new MoveTo("p-1", new Tile(1, 1), 3))));
    }

    @Test
    void refusesATileThatCannotBeReached() {
        Room walled = new Room("walled", Grid.parse(
                "..#.",
                "..#.",
                "..#."), SPAWN);
        walled.tick(List.of(new Join("p-1")));

        assertEquals(
                List.of(new Outgoing(new OnlyPlayer("p-1"), new MoveRejected("p-1", Reason.UNREACHABLE, 4))),
                walled.tick(List.of(new MoveTo("p-1", new Tile(3, 1), 4))));
    }

    @Test
    void ignoresAMoveOfAnUnknownPlayer() {
        assertTrue(room.tick(List.of(new MoveTo("nobody", new Tile(1, 0), 1))).isEmpty());
    }

    @Test
    void ignoresAMoveToTheTileThePlayerStandsOn() {
        room.tick(List.of(new Join("p-1")));

        assertTrue(room.tick(List.of(new MoveTo("p-1", SPAWN, 1))).isEmpty());
    }

    // Moving: walking

    @Test
    void everyoneGetsThePathFromThePlayersTileToTheGoal() {
        room.tick(List.of(new Join("p-1"), new Join("p-2")));

        List<Outgoing> events = room.tick(List.of(new MoveTo("p-1", new Tile(3, 0), 1)));

        var path = new PathStarted("p-1", List.of(new Tile(0, 0), new Tile(1, 0), new Tile(2, 0), new Tile(3, 0)));
        assertEquals(List.of(new Outgoing(new Everyone(), path)), events);
    }

    @Test
    void takesOneStepEveryStepTicks() {
        room.tick(List.of(new Join("p-1")));
        room.tick(List.of(new MoveTo("p-1", new Tile(2, 0), 1)));

        idle(Room.STEP_TICKS - 2);
        assertEquals(new Tile(0, 0), look().get("p-1").position()); // one tick before the step
        assertEquals(new Tile(1, 0), look().get("p-1").position()); // STEP_TICKS ticks after the move

        idle(Room.STEP_TICKS - 1);
        assertEquals(new PlayerView("p-1", new Tile(2, 0), List.of()), look().get("p-1")); // arrived, standing
    }

    @Test
    void aSnapshotShowsTheTilesStillToWalk() {
        room.tick(List.of(new Join("p-1")));
        room.tick(List.of(new MoveTo("p-1", new Tile(3, 0), 1)));

        assertEquals(
                new PlayerView("p-1", new Tile(0, 0), List.of(new Tile(1, 0), new Tile(2, 0), new Tile(3, 0))),
                look().get("p-1"));
    }

    @Test
    void aNewGoalStartsFromTheTileTheStepInProgressReaches() {
        room.tick(List.of(new Join("p-1")));
        room.tick(List.of(new MoveTo("p-1", new Tile(3, 0), 1)));
        idle(2);

        var path = (PathStarted) room.tick(List.of(new MoveTo("p-1", new Tile(0, 2), 2))).getFirst().event();

        assertEquals(new Tile(1, 0), path.tiles().getFirst());
        assertEquals(new Tile(0, 2), path.tiles().getLast());
        // The step in progress keeps its timing: the player reaches (1,0) STEP_TICKS ticks after
        // the first move. Three ticks have passed since then.
        idle(Room.STEP_TICKS - 5);
        assertEquals(new Tile(0, 0), look().get("p-1").position());
        assertEquals(new Tile(1, 0), look().get("p-1").position());
    }

    @Test
    void aNewGoalOnTheTileBeingReachedStopsThere() {
        room.tick(List.of(new Join("p-1")));
        room.tick(List.of(new MoveTo("p-1", new Tile(3, 0), 1)));

        var path = (PathStarted) room.tick(List.of(new MoveTo("p-1", new Tile(1, 0), 2))).getFirst().event();

        assertEquals(List.of(new Tile(1, 0)), path.tiles());
        idle(Room.STEP_TICKS);
        assertEquals(new PlayerView("p-1", new Tile(1, 0), List.of()), look().get("p-1"));
    }

    @Test
    void playersCanStandOnTheSameTile() {
        room.tick(List.of(new Join("p-1"), new Join("p-2")));
        room.tick(List.of(new MoveTo("p-1", new Tile(1, 0), 1), new MoveTo("p-2", new Tile(1, 0), 1)));

        idle(Room.STEP_TICKS);

        Map<String, PlayerView> players = look();
        assertEquals(new Tile(1, 0), players.get("p-1").position());
        assertEquals(new Tile(1, 0), players.get("p-2").position());
    }

    // Helpers

    private static PlayerView standing(String playerId) {
        return new PlayerView(playerId, SPAWN, List.of());
    }

    /** Lets a new player join and returns the players in the snapshot they receive. */
    private List<PlayerView> playersSeenBy(String newPlayerId) {
        var snapshot = (RoomSnapshot) room.tick(List.of(new Join(newPlayerId))).getFirst().event();
        return snapshot.players().subList(0, snapshot.players().size() - 1);
    }

    private int observers;

    private void idle(int ticks) {
        for (int i = 0; i < ticks; i++) {
            assertTrue(room.tick(List.of()).isEmpty());
        }
    }

    /**
     * Runs one tick in which a new observer joins, and returns the other players in the snapshot
     * the observer receives, by id. It counts as a tick: walking players move first.
     */
    private Map<String, PlayerView> look() {
        return playersSeenBy("observer-" + ++observers).stream()
                .filter(player -> !player.playerId().startsWith("observer-"))
                .collect(Collectors.toMap(PlayerView::playerId, player -> player));
    }
}
