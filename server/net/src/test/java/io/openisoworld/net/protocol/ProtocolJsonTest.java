package io.openisoworld.net.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks that the records generated from protocol/schemas read and write the JSON of the schemas. */
class ProtocolJsonTest {

    // The WebSocket must use the same settings. In Jackson 3 FAIL_ON_UNKNOWN_PROPERTIES is off by
    // default: without it, a field the schema does not allow would pass silently.
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** Lets the JSON of the tests use single quotes: {'x': 1} becomes {"x": 1}. */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    // Writing

    @Test
    void writesFieldNamesInCamelCase() {
        var path = new PathPayload("p-1", List.of(new Tile(3, 7)));

        assertEquals(json("{'playerId':'p-1','tiles':[{'x':3,'y':7}]}"), MAPPER.writeValueAsString(path));
    }

    @Test
    void writesTheErrorCodeAsInTheSchemaAndLeavesOutAMissingRefSeq() {
        var error = new ErrorPayload(ErrorCode.INVALID_TILE, "Tile is blocked", null);

        assertEquals(
                json("{'code':'invalidTile','message':'Tile is blocked'}"), MAPPER.writeValueAsString(error));
    }

    @Test
    void writesRefSeqWhenThereIsOne() {
        var error = new ErrorPayload(ErrorCode.RATE_LIMITED, "Too many moves", 12);

        assertEquals(
                json("{'code':'rateLimited','message':'Too many moves','refSeq':12}"),
                MAPPER.writeValueAsString(error));
    }

    @Test
    void writesTheEnvelope() {
        var envelope = new Envelope<>(PathPayload.TYPE, 8, new PathPayload("p-1", List.of(new Tile(0, 0))));

        assertEquals(
                json("{'type':'path','seq':8,'payload':{'playerId':'p-1','tiles':[{'x':0,'y':0}]}}"),
                MAPPER.writeValueAsString(envelope));
    }

    // Reading

    @Test
    void readsHelloWithAndWithoutTheClientVersion() {
        var full = MAPPER.readValue(json("{'protocolVersion':1,'clientVersion':'0.1.0'}"), HelloPayload.class);
        var minimal = MAPPER.readValue(json("{'protocolVersion':1}"), HelloPayload.class);

        assertEquals(new HelloPayload(1, "0.1.0"), full);
        assertNull(minimal.clientVersion());
    }

    @Test
    void readsAWholeRoomState() {
        var text = json("""
                {
                  'roomId': 'lobby',
                  'width': 10,
                  'height': 8,
                  'blocked': [{ 'x': 2, 'y': 2 }],
                  'players': [
                    { 'playerId': 'p-1', 'position': { 'x': 0, 'y': 0 } },
                    { 'playerId': 'p-2', 'position': { 'x': 3, 'y': 1 },
                      'path': [{ 'x': 4, 'y': 1 }, { 'x': 5, 'y': 1 }] }
                  ]
                }
                """);
        var expected = new RoomStatePayload(
                "lobby",
                10,
                8,
                List.of(new Tile(2, 2)),
                List.of(
                        new Player("p-1", new Tile(0, 0), null),
                        new Player("p-2", new Tile(3, 1), List.of(new Tile(4, 1), new Tile(5, 1)))));

        assertEquals(expected, MAPPER.readValue(text, RoomStatePayload.class));
    }

    @Test
    void acceptsTheCornerOfTheRoom() {
        assertEquals(new MoveToPayload(0, 0), MAPPER.readValue(json("{'x':0,'y':0}"), MoveToPayload.class));
    }

    // Refusals

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of(HelloPayload.class, "{}"),
                Arguments.of(MoveToPayload.class, "{'x':1}"),
                Arguments.of(MoveToPayload.class, "{'x':1,'y':2,'z':3}"),
                Arguments.of(MoveToPayload.class, "{'x':null,'y':2}"),
                Arguments.of(MoveToPayload.class, "{'x':-1,'y':2}"),
                Arguments.of(ErrorPayload.class, "{'code':'notACode','message':'x'}"),
                Arguments.of(PathPayload.class, "{'playerId':'p-1','tiles':[]}"),
                Arguments.of(WelcomePayload.class, "{'playerId':''}"),
                Arguments.of(HelloPayload.class, "{'protocolVersion':1,'clientVersion':'" + "v".repeat(33) + "'}"),
                Arguments.of(ErrorPayload.class, "{'code':'invalidTile','message':'" + "m".repeat(201) + "'}"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidPayloads")
    void refusesAPayloadOutsideTheSchema(Class<?> type, String text) {
        assertThrows(JacksonException.class, () -> MAPPER.readValue(json(text), type));
    }

    // Constructors

    @Test
    void refusesAMissingRequiredValue() {
        var error = assertThrows(NullPointerException.class, () -> new PathPayload(null, List.of(new Tile(0, 0))));

        assertEquals("playerId", error.getMessage());
    }

    @Test
    void keepsItsListsUnchangeable() {
        var tiles = new ArrayList<>(List.of(new Tile(0, 0)));
        var path = new PathPayload("p-1", tiles);

        tiles.add(new Tile(1, 0));

        assertEquals(List.of(new Tile(0, 0)), path.tiles());
        assertThrows(UnsupportedOperationException.class, () -> path.tiles().add(new Tile(2, 0)));
    }

    // Message types

    @Test
    void mapsEveryClientTypeToItsPayload() {
        assertEquals(
                Map.of("hello", HelloPayload.class, "moveTo", MoveToPayload.class), MessageTypes.CLIENT);
    }

    @Test
    void givesTheTypeOfEveryServerPayload() {
        var tile = new Tile(0, 0);
        var player = new Player("p-1", tile, null);

        assertEquals("error", MessageTypes.typeOf(new ErrorPayload(ErrorCode.INVALID_MESSAGE, "Bad", null)));
        assertEquals("path", MessageTypes.typeOf(new PathPayload("p-1", List.of(tile))));
        assertEquals("playerJoined", MessageTypes.typeOf(new PlayerJoinedPayload(player)));
        assertEquals("playerLeft", MessageTypes.typeOf(new PlayerLeftPayload("p-1")));
        assertEquals("roomState", MessageTypes.typeOf(new RoomStatePayload("lobby", 1, 1, List.of(), List.of())));
        assertEquals("welcome", MessageTypes.typeOf(new WelcomePayload("p-1")));
    }

    @Test
    void readsAClientMessageInTwoSteps() {
        // First the envelope, to learn the type; then the payload, with the class of that type.
        JsonNode frame = MAPPER.readTree(json("{'type':'moveTo','seq':3,'payload':{'x':4,'y':7}}"));
        Class<? extends ClientPayload> type = MessageTypes.CLIENT.get(frame.get("type").stringValue());

        ClientPayload payload = MAPPER.treeToValue(frame.get("payload"), type);

        assertEquals(new MoveToPayload(4, 7), payload);
    }
}
