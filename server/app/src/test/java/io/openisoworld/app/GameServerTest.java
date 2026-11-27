package io.openisoworld.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocketHandshakeException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * The whole server, Tomcat included, driven by clients over real connections: ticket, hello,
 * welcome, roomState, moveTo, path. The room is shared by the tests, so each test only looks at
 * messages about its own players.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GameServerTest {

    private static final String HELLO = "{\"protocolVersion\":1}";

    @LocalServerPort
    private int port;

    private static boolean isPlayer(JsonNode payload, String playerId) {
        return payload.get("playerId").asString().equals(playerId);
    }

    private static boolean tileIs(JsonNode tile, int x, int y) {
        return tile.get("x").asInt() == x && tile.get("y").asInt() == y;
    }

    // Entering

    @Test
    void welcomesAGuestAndDescribesTheRoom() throws Exception {
        try (TestClient client = TestClient.connect(port)) {
            client.send("hello", HELLO);

            String me = TestClient.playerId(client.await("welcome"));
            JsonNode room = client.await("roomState").get("payload");

            assertEquals("lobby", room.get("roomId").asString());
            assertEquals(10, room.get("width").asInt());
            assertEquals(10, room.get("height").asInt());
            JsonNode self = findPlayer(room, me);
            assertTrue(tileIs(self.get("position"), 1, 1), self.toString());
        }
    }

    @Test
    void tellsThePlayersInTheRoomWhoArrives() throws Exception {
        try (TestClient first = TestClient.enter(port); TestClient second = TestClient.connect(port)) {
            second.send("hello", HELLO);
            String newcomer = TestClient.playerId(second.await("welcome"));

            first.await("playerJoined", payload -> isPlayer(payload.get("player"), newcomer));
            JsonNode room = second.await("roomState").get("payload");
            assertTrue(room.get("players").size() >= 2);
        }
    }

    // Moving

    @Test
    void sendsThePathToEveryone() throws Exception {
        try (TestClient walker = TestClient.connect(port); TestClient watcher = TestClient.enter(port)) {
            walker.send("hello", HELLO);
            String me = TestClient.playerId(walker.await("welcome"));
            walker.await("roomState");

            walker.send("moveTo", "{\"x\":5,\"y\":1}");

            for (TestClient client : List.of(walker, watcher)) {
                JsonNode tiles = client.await("path", payload -> isPlayer(payload, me)).get("payload").get("tiles");
                assertTrue(tileIs(tiles.get(0), 1, 1), tiles.toString());
                assertTrue(tileIs(tiles.get(tiles.size() - 1), 5, 1), tiles.toString());
            }
        }
    }

    @Test
    void refusesABlockedTileOnlyToTheSender() throws Exception {
        try (TestClient walker = TestClient.enter(port); TestClient watcher = TestClient.enter(port)) {
            int seq = walker.send("moveTo", "{\"x\":2,\"y\":2}"); // the 2 x 2 block of the lobby

            JsonNode error = walker.await("error").get("payload");
            assertEquals("invalidTile", error.get("code").asString());
            assertEquals(seq, error.get("refSeq").asInt());
            assertTrue(watcher.receivesNo("error"));
        }
    }

    @Test
    void refusesATileOutsideTheRoom() throws Exception {
        try (TestClient walker = TestClient.enter(port)) {
            int seq = walker.send("moveTo", "{\"x\":10,\"y\":0}");

            JsonNode error = walker.await("error").get("payload");
            assertEquals("invalidTile", error.get("code").asString());
            assertEquals(seq, error.get("refSeq").asInt());
        }
    }

    // Leaving

    @Test
    void tellsTheOthersWhenAPlayerLeaves() throws Exception {
        try (TestClient watcher = TestClient.enter(port)) {
            String leaver;
            try (TestClient client = TestClient.connect(port)) {
                client.send("hello", HELLO);
                leaver = TestClient.playerId(client.await("welcome"));
                client.await("roomState");
            }

            watcher.await("playerLeft", payload -> isPlayer(payload, leaver));
        }
    }

    // Tickets

    @Test
    void refusesTheWebSocketWithoutATicket() {
        assertEquals(401, handshakeStatus("ws://localhost:" + port + "/ws"));
    }

    @Test
    void refusesAnUnknownTicket() {
        assertEquals(401, handshakeStatus("ws://localhost:" + port + "/ws?ticket=made-up"));
    }

    @Test
    void refusesATicketUsedBefore() throws Exception {
        String ticket = TestClient.ticket(port);
        TestClient.open(port, ticket).close();

        assertEquals(401, handshakeStatus("ws://localhost:" + port + "/ws?ticket=" + ticket));
    }

    // Protocol errors

    @Test
    void closesAfterAnUnsupportedVersion() throws Exception {
        try (TestClient client = TestClient.connect(port)) {
            int seq = client.send("hello", "{\"protocolVersion\":2}");

            JsonNode error = client.await("error").get("payload");
            assertEquals("unsupportedProtocolVersion", error.get("code").asString());
            assertEquals(seq, error.get("refSeq").asInt());
            assertEquals(1002, client.awaitClose());
        }
    }

    @Test
    void answersABadFrameAndGoesOn() throws Exception {
        try (TestClient client = TestClient.enter(port)) {
            client.sendRaw("{\"type\":\"moveTo\",\"seq\":40,\"payload\":{\"x\":\"far\"}}");

            JsonNode error = client.await("error").get("payload");
            assertEquals("invalidMessage", error.get("code").asString());
            assertEquals(40, error.get("refSeq").asInt());

            client.send("moveTo", "{\"x\":1,\"y\":5}");
            client.await("path", payload -> tileIs(last(payload.get("tiles")), 1, 5));
        }
    }

    @Test
    void numbersTheServerMessagesWithoutGaps() throws Exception {
        try (TestClient client = TestClient.enter(port)) {
            client.send("moveTo", "{\"x\":0,\"y\":5}");
            client.await("path", payload -> tileIs(last(payload.get("tiles")), 0, 5));

            List<Integer> seqs = client.seqs();
            assertEquals(IntStream.rangeClosed(1, seqs.size()).boxed().toList(), seqs);
        }
    }

    // HTTP security

    @Test
    void opensOnlyTheTicketEndpointAndTheWebSocket() throws Exception {
        assertEquals(403, httpGet("/api/tickets").statusCode());
        assertEquals(403, httpGet("/").statusCode());
        assertEquals(403, httpGet("/actuator").statusCode());
    }

    @Test
    void issuesTicketsThatNoCacheKeeps() throws Exception {
        HttpResponse<String> response = TestClient.requestTicket(port);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(response.body().matches("\\{\"ticket\":\"[A-Za-z0-9_-]{43}\"}"), response.body());
    }

    // Helpers

    private static JsonNode findPlayer(JsonNode room, String playerId) {
        List<JsonNode> found = new ArrayList<>();
        room.get("players").forEach(player -> {
            if (isPlayer(player, playerId)) {
                found.add(player);
            }
        });
        assertEquals(1, found.size(), room.toString());
        return found.getFirst();
    }

    private static JsonNode last(JsonNode array) {
        return array.get(array.size() - 1);
    }

    private int handshakeStatus(String uri) {
        CompletionException refused = assertThrows(CompletionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(uri), new java.net.http.WebSocket.Listener() {})
                .join());
        return assertInstanceOf(WebSocketHandshakeException.class, refused.getCause())
                .getResponse()
                .statusCode();
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
