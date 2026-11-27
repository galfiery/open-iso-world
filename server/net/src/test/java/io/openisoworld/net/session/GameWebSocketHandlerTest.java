package io.openisoworld.net.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Command;
import io.openisoworld.core.room.Outgoing;
import io.openisoworld.core.room.Recipients.OnlyPlayer;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.net.message.MessageCodec;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

/** Drives the handler with a fake session; the room is a list that collects the commands. */
class GameWebSocketHandlerTest {

    private static final Duration HELLO_TIMEOUT = Duration.ofMillis(100);

    private final List<Command> commands = new CopyOnWriteArrayList<>();
    private final RoomBroadcaster broadcaster = new RoomBroadcaster();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private long now; // the handler's clock for the message limit, moved by hand
    private final GameWebSocketHandler handler = new GameWebSocketHandler(
            commands::add, broadcaster, new MessageCodec(), scheduler, HELLO_TIMEOUT, () -> now);
    private final FakeSession session = new FakeSession("s-1");

    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    @BeforeEach
    void open() {
        session.getAttributes().put(TicketHandshakeInterceptor.PLAYER_ID, "p-1");
        handler.afterConnectionEstablished(session);
    }

    @AfterEach
    void close() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        scheduler.shutdownNow();
    }

    private void receive(String singleQuoted) {
        handler.handleTextMessage(session, new TextMessage(json(singleQuoted)));
    }

    private void hello() {
        receive("{'type':'hello','seq':1,'payload':{'protocolVersion':1}}");
    }

    @Test
    void welcomesThePlayerAndLetsThemIntoTheRoom() throws InterruptedException {
        hello();

        assertEquals(json("[{'type':'welcome','seq':1,'payload':{'playerId':'p-1'}}]"), session.nextFrame());
        assertEquals(List.of(new Command.Join("p-1")), commands);
    }

    @Test
    void refusesAnUnsupportedVersionAndCloses() throws InterruptedException {
        receive("{'type':'hello','seq':1,'payload':{'protocolVersion':2}}");

        String frame = session.nextFrame();
        assertTrue(frame.contains(json("'code':'unsupportedProtocolVersion'")), frame);
        assertTrue(frame.contains(json("'refSeq':1")), frame);
        assertEquals(GameWebSocketHandler.UNSUPPORTED_VERSION, session.awaitClose());
        assertEquals(1002, session.awaitClose().getCode());
        assertEquals(List.of(), commands);
    }

    @Test
    void refusesASecondHello() throws InterruptedException {
        hello();
        session.nextFrame();

        receive("{'type':'hello','seq':2,'payload':{'protocolVersion':1}}");

        String frame = session.nextFrame();
        assertTrue(frame.contains(json("'code':'invalidMessage'")) && frame.contains(json("'refSeq':2")), frame);
        assertEquals(List.of(new Command.Join("p-1")), commands);
    }

    @Test
    void refusesAMoveBeforeHello() throws InterruptedException {
        receive("{'type':'moveTo','seq':1,'payload':{'x':3,'y':2}}");

        String frame = session.nextFrame();
        assertTrue(frame.contains(json("'code':'invalidMessage','message':'Send hello first','refSeq':1")), frame);
        assertEquals(List.of(), commands);
    }

    @Test
    void sendsAMoveToTheRoom() {
        hello();

        receive("{'type':'moveTo','seq':2,'payload':{'x':3,'y':2}}");

        assertEquals(new Command.MoveTo("p-1", new Tile(3, 2), 2), commands.getLast());
    }

    @Test
    void answersABadFrameAndStaysOpen() throws InterruptedException {
        hello();
        session.nextFrame();

        receive("{'type':'moveTo','seq':2,'payload':{'x':-1,'y':2}}");
        receive("{'type':'moveTo','seq':3,'payload':{'x':3,'y':2}}");

        String frame = session.nextFrame();
        assertTrue(frame.contains(json("'code':'invalidMessage'")) && frame.contains(json("'refSeq':2")), frame);
        assertTrue(session.isOpen());
        assertEquals(new Command.MoveTo("p-1", new Tile(3, 2), 3), commands.getLast());
    }

    @Test
    void leavesTheRoomWhenTheSessionCloses() {
        hello();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(List.of(new Command.Join("p-1"), new Command.Leave("p-1")), commands);
    }

    @Test
    void sendsNothingToTheRoomWhenClosedBeforeHello() {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertEquals(List.of(), commands);
    }

    @Test
    void handsTheConnectionToTheBroadcasterAfterHelloAndTakesItBackOnClose() {
        Outgoing toPlayer = new Outgoing(new OnlyPlayer("p-1"), new PlayerLeft("x"));
        assertTrue(broadcaster.route(List.of(toPlayer)).isEmpty()); // before hello

        hello();
        assertEquals(List.of("p-1"), List.copyOf(broadcaster.route(List.of(toPlayer)).keySet()));

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertTrue(broadcaster.route(List.of(toPlayer)).isEmpty());
    }

    // Limits

    @Test
    void closesAfterMoreThanTwentyMessagesAtOnce() throws InterruptedException {
        hello();
        for (int seq = 2; seq <= MessageRateLimiter.CAPACITY + 1; seq++) {
            receive("{'type':'moveTo','seq':" + seq + ",'payload':{'x':3,'y':2}}");
        }

        assertEquals(GameWebSocketHandler.TOO_MANY_MESSAGES, session.awaitClose());
        assertEquals(1008, session.awaitClose().getCode());
        // hello and 19 moves were let through; the twenty-first message and what follows were not.
        assertEquals(MessageRateLimiter.CAPACITY, commands.size());
    }

    @Test
    void countsBadFramesTooAgainstTheLimit() throws InterruptedException {
        for (int i = 0; i <= MessageRateLimiter.CAPACITY; i++) {
            receive("not json");
        }

        assertEquals(GameWebSocketHandler.TOO_MANY_MESSAGES, session.awaitClose());
    }

    @Test
    void allowsTwentyMessagesEverySecond() {
        hello();
        for (int second = 0; second < 3; second++) {
            now += Duration.ofSeconds(1).toNanos();
            for (int i = 0; i < MessageRateLimiter.CAPACITY; i++) {
                receive("{'type':'moveTo','seq':2,'payload':{'x':3,'y':2}}");
            }
        }

        assertTrue(session.isOpen());
        assertEquals(1 + 3 * MessageRateLimiter.CAPACITY, commands.size());
    }

    @Test
    void closesAConnectionThatNeverSaysHello() throws InterruptedException {
        assertEquals(GameWebSocketHandler.NO_HELLO, session.awaitClose());
        assertEquals(1008, session.awaitClose().getCode());
    }

    @Test
    void keepsAConnectionThatSaidHelloInTime() throws InterruptedException {
        hello();

        Thread.sleep(HELLO_TIMEOUT.multipliedBy(3).toMillis());

        assertTrue(session.isOpen());
    }

    @Test
    void ignoresWhatArrivesAfterTheConnectionStartedClosing() {
        receive("{'type':'hello','seq':1,'payload':{'protocolVersion':2}}"); // closes after the error
        hello();

        assertEquals(List.of(), commands);
    }
}
