package io.openisoworld.net.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Command;
import io.openisoworld.core.room.PlayerView;
import io.openisoworld.core.room.RoomEvent;
import io.openisoworld.core.room.RoomEvent.MoveRejected;
import io.openisoworld.core.room.RoomEvent.MoveRejected.Reason;
import io.openisoworld.core.room.RoomEvent.PathStarted;
import io.openisoworld.core.room.RoomEvent.PlayerJoined;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import io.openisoworld.net.protocol.ErrorCode;
import io.openisoworld.net.protocol.ErrorPayload;
import io.openisoworld.net.protocol.MoveToPayload;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks each core event against the JSON the protocol expects, as the WebSocket writes it. */
class TranslatorTest {

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private static String written(RoomEvent event) {
        return MessageCodec.mapper().writeValueAsString(Translator.toPayload(event));
    }

    private static final PlayerView STANDING = new PlayerView("p-1", new Tile(1, 1), List.of());
    private static final PlayerView WALKING = new PlayerView("p-2", new Tile(3, 1), List.of(new Tile(4, 1), new Tile(5, 2)));

    @Test
    void translatesTheSnapshotIntoRoomState() {
        var snapshot = new RoomSnapshot("lobby", 10, 8, List.of(new Tile(2, 2)), List.of(STANDING, WALKING));

        assertEquals(
                json("{'roomId':'lobby','width':10,'height':8,'blocked':[{'x':2,'y':2}],'players':["
                        + "{'playerId':'p-1','position':{'x':1,'y':1}},"
                        + "{'playerId':'p-2','position':{'x':3,'y':1},'path':[{'x':4,'y':1},{'x':5,'y':2}]}]}"),
                written(snapshot));
    }

    @Test
    void translatesAJoinWithoutPathForAPlayerStandingStill() {
        assertEquals(
                json("{'player':{'playerId':'p-1','position':{'x':1,'y':1}}}"),
                written(new PlayerJoined(STANDING)));
    }

    @Test
    void translatesALeave() {
        assertEquals(json("{'playerId':'p-1'}"), written(new PlayerLeft("p-1")));
    }

    @Test
    void translatesAPath() {
        var path = new PathStarted("p-1", List.of(new Tile(1, 1), new Tile(2, 2)));

        assertEquals(
                json("{'playerId':'p-1','tiles':[{'x':1,'y':1},{'x':2,'y':2}]}"), written(path));
    }

    @Test
    void translatesTheRefusalsOfATileIntoInvalidTile() {
        for (Reason reason : List.of(Reason.OUTSIDE_ROOM, Reason.BLOCKED, Reason.UNREACHABLE)) {
            var error = (ErrorPayload) Translator.toPayload(new MoveRejected("p-1", reason, 12));

            assertEquals(ErrorCode.INVALID_TILE, error.code(), reason.name());
        }
    }

    @Test
    void givesEachRefusalOfATileItsOwnMessage() {
        long messages = List.of(Reason.OUTSIDE_ROOM, Reason.BLOCKED, Reason.UNREACHABLE).stream()
                .map(reason -> ((ErrorPayload) Translator.toPayload(new MoveRejected("p-1", reason, 1))).message())
                .distinct()
                .count();

        assertEquals(3, messages);
    }

    @Test
    void translatesTheRateLimit() {
        assertEquals(
                json("{'code':'rateLimited','message':'Too many moves: about 5 a second are allowed','refSeq':12}"),
                written(new MoveRejected("p-1", Reason.RATE_LIMITED, 12)));
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void pointsEveryRefusalBackToTheMove(Reason reason) {
        var error = (ErrorPayload) Translator.toPayload(new MoveRejected("p-1", reason, 12));

        assertEquals(12, error.refSeq());
        assertTrue(error.message().length() <= 200);
    }

    @Test
    void turnsMoveToIntoACommand() {
        assertEquals(
                new Command.MoveTo("p-1", new Tile(4, 7), 12),
                Translator.toCommand("p-1", 12, new MoveToPayload(4, 7)));
    }
}
