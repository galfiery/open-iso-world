package io.openisoworld.core.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
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

    // Helpers

    private static PlayerView standing(String playerId) {
        return new PlayerView(playerId, SPAWN, List.of());
    }

    /** Lets a new player join and returns the players in the snapshot they receive. */
    private List<PlayerView> playersSeenBy(String newPlayerId) {
        var snapshot = (RoomSnapshot) room.tick(List.of(new Join(newPlayerId))).getFirst().event();
        return snapshot.players().subList(0, snapshot.players().size() - 1);
    }
}
