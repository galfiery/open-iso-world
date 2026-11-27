package io.openisoworld.net.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Outgoing;
import io.openisoworld.core.room.PlayerView;
import io.openisoworld.core.room.Recipients.Everyone;
import io.openisoworld.core.room.Recipients.EveryoneExcept;
import io.openisoworld.core.room.Recipients.OnlyPlayer;
import io.openisoworld.core.room.RoomEvent.PathStarted;
import io.openisoworld.core.room.RoomEvent.PlayerJoined;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.protocol.PathPayload;
import io.openisoworld.net.protocol.PlayerJoinedPayload;
import io.openisoworld.net.protocol.PlayerLeftPayload;
import io.openisoworld.net.protocol.RoomStatePayload;
import io.openisoworld.net.protocol.ServerPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoomBroadcasterTest {

    private final MessageCodec codec = new MessageCodec();
    private final RoomBroadcaster broadcaster = new RoomBroadcaster();
    private final List<Connection> connections = new ArrayList<>();
    private final Map<String, FakeSession> sessions = new java.util.HashMap<>();

    @AfterEach
    void stop() {
        connections.forEach(Connection::stop);
    }

    private void connect(String playerId) {
        var session = new FakeSession("s-" + playerId);
        var connection = new Connection(playerId, session, codec);
        sessions.put(playerId, session);
        connections.add(connection);
        broadcaster.add(connection);
    }

    /** Connects players and lets them receive their roomState, so they are in the room. */
    private void enter(String... playerIds) {
        for (String playerId : playerIds) {
            connect(playerId);
            broadcaster.route(List.of(new Outgoing(new OnlyPlayer(playerId), snapshot())));
        }
    }

    private static RoomSnapshot snapshot() {
        return new RoomSnapshot("lobby", 3, 3, List.of(), List.of());
    }

    private static Outgoing path(String playerId) {
        return new Outgoing(new Everyone(), new PathStarted(playerId, List.of(new Tile(0, 0))));
    }

    private static List<Class<?>> types(List<ServerPayload> payloads) {
        return payloads.stream().<Class<?>>map(Object::getClass).toList();
    }

    @Test
    void sendsEveryoneToAllPlayersInTheRoom() {
        enter("a", "b");

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(path("a")));

        assertEquals(List.of("a", "b"), List.copyOf(frames.keySet()));
    }

    @Test
    void sendsOnlyPlayerToThatPlayer() {
        enter("a", "b");

        Map<String, List<ServerPayload>> frames =
                broadcaster.route(List.of(new Outgoing(new OnlyPlayer("b"), new PlayerLeft("x"))));

        assertEquals(List.of("b"), List.copyOf(frames.keySet()));
    }

    @Test
    void sendsEveryoneExceptToTheOthers() {
        enter("a", "b", "c");
        var joined = new PlayerJoined(new PlayerView("b", new Tile(1, 1), List.of()));

        Map<String, List<ServerPayload>> frames =
                broadcaster.route(List.of(new Outgoing(new EveryoneExcept("b"), joined)));

        assertEquals(List.of("a", "c"), List.copyOf(frames.keySet()));
    }

    @Test
    void groupsATickIntoOneListPerPlayerInOrder() {
        enter("a", "b");

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(
                path("a"), new Outgoing(new OnlyPlayer("a"), new PlayerLeft("x")), path("b")));

        assertEquals(
                List.of(PathPayload.class, PlayerLeftPayload.class, PathPayload.class), types(frames.get("a")));
        assertEquals(List.of(PathPayload.class, PathPayload.class), types(frames.get("b")));
    }

    @Test
    void sharesOnePayloadAmongTheRecipients() {
        enter("a", "b");

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(path("a")));

        assertTrue(frames.get("a").getFirst() == frames.get("b").getFirst());
    }

    @Test
    void sendsNothingForEveryoneBeforeTheRoomState() {
        enter("a");
        connect("b"); // said hello, but the room has not seen the Join yet

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(path("a")));

        assertEquals(List.of("a"), List.copyOf(frames.keySet()));
    }

    @Test
    void sendsEverythingAfterTheRoomStateInTheSameTick() {
        enter("a");
        connect("b");
        var joined = new PlayerJoined(new PlayerView("b", new Tile(1, 1), List.of()));

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(
                path("a"), // before b's roomState: not for b
                new Outgoing(new OnlyPlayer("b"), snapshot()),
                new Outgoing(new EveryoneExcept("b"), joined),
                path("b")));

        assertEquals(List.of(PathPayload.class, PlayerJoinedPayload.class, PathPayload.class), types(frames.get("a")));
        assertEquals(List.of(RoomStatePayload.class, PathPayload.class), types(frames.get("b")));
    }

    @Test
    void dropsEventsForADisconnectedPlayer() {
        enter("a", "b");
        broadcaster.remove("b");

        Map<String, List<ServerPayload>> frames = broadcaster.route(List.of(
                new Outgoing(new OnlyPlayer("b"), new PlayerLeft("x")), path("a")));

        assertEquals(List.of("a"), List.copyOf(frames.keySet()));
    }

    @Test
    void stopsSendingToEveryoneAfterTheirPlayerLeft() {
        enter("a", "b");

        Map<String, List<ServerPayload>> frames =
                broadcaster.route(List.of(new Outgoing(new Everyone(), new PlayerLeft("b")), path("a")));

        assertEquals(List.of("a"), List.copyOf(frames.keySet()));
    }

    @Test
    void writesOneFramePerPlayerPerTick() throws InterruptedException {
        connect("a");
        connect("b");

        broadcaster.onTick("lobby", List.of(new Outgoing(new OnlyPlayer("a"), snapshot()), path("a")));
        broadcaster.onTick("lobby", List.of(new Outgoing(new OnlyPlayer("b"), snapshot()), path("b")));

        String a1 = sessions.get("a").nextFrame();
        assertTrue(a1.startsWith("[{\"type\":\"roomState\",\"seq\":1"), a1);
        assertTrue(a1.contains("{\"type\":\"path\",\"seq\":2"), a1);
        assertTrue(sessions.get("a").nextFrame().startsWith("[{\"type\":\"path\",\"seq\":3"));
        String b1 = sessions.get("b").nextFrame();
        assertTrue(b1.startsWith("[{\"type\":\"roomState\",\"seq\":1") && b1.contains("\"seq\":2"), b1);
        assertTrue(sessions.get("b").sentNothingMore());
    }
}
