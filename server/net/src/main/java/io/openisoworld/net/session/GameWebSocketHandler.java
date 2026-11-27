package io.openisoworld.net.session;

import io.openisoworld.core.room.Command;
import io.openisoworld.net.message.Incoming;
import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.message.Translator;
import io.openisoworld.net.protocol.ErrorCode;
import io.openisoworld.net.protocol.ErrorPayload;
import io.openisoworld.net.protocol.HelloPayload;
import io.openisoworld.net.protocol.MoveToPayload;
import io.openisoworld.net.protocol.ProtocolVersion;
import io.openisoworld.net.protocol.WelcomePayload;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * One WebSocket session from open to close: hello, moves, errors and the way out.
 *
 * <p>The handler decides nothing about the game: it reads frames, turns them into room commands
 * and answers what the room cannot see, such as a bad frame or a wrong protocol version. Binary
 * frames are refused by {@link TextWebSocketHandler}, which closes with 1003.
 *
 * <p>It also protects the server from clients that misbehave: more than about twenty messages a
 * second, or no hello within {@link #HELLO_TIMEOUT}, close the connection with 1008.
 */
public final class GameWebSocketHandler extends TextWebSocketHandler {

    /** How long a new connection may stay silent before its hello. */
    public static final Duration HELLO_TIMEOUT = Duration.ofSeconds(10);

    static final CloseStatus UNSUPPORTED_VERSION =
            CloseStatus.PROTOCOL_ERROR.withReason("Unsupported protocol version");
    static final CloseStatus TOO_MANY_MESSAGES = CloseStatus.POLICY_VIOLATION.withReason("Too many messages");
    static final CloseStatus NO_HELLO = CloseStatus.POLICY_VIOLATION.withReason("No hello");

    private static final System.Logger LOG = System.getLogger(GameWebSocketHandler.class.getName());

    private final Consumer<Command> room;
    private final RoomBroadcaster broadcaster;
    private final MessageCodec codec;
    private final ScheduledExecutorService scheduler;
    private final Duration helloTimeout;
    private final LongSupplier nanoTime;
    private final Map<String, Client> clients = new ConcurrentHashMap<>(); // by session id

    /**
     * {@code room} takes commands for the room, for example {@code roomLoop::submit}. The scheduler
     * only runs the hello timeouts, which queue a close and return: it can be the rooms' scheduler.
     */
    public GameWebSocketHandler(
            Consumer<Command> room,
            RoomBroadcaster broadcaster,
            MessageCodec codec,
            ScheduledExecutorService scheduler) {
        this(room, broadcaster, codec, scheduler, HELLO_TIMEOUT, System::nanoTime);
    }

    GameWebSocketHandler(
            Consumer<Command> room,
            RoomBroadcaster broadcaster,
            MessageCodec codec,
            ScheduledExecutorService scheduler,
            Duration helloTimeout,
            LongSupplier nanoTime) {
        this.room = room;
        this.broadcaster = broadcaster;
        this.codec = codec;
        this.scheduler = scheduler;
        this.helloTimeout = helloTimeout;
        this.nanoTime = nanoTime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!(session.getAttributes().get(TicketHandshakeInterceptor.PLAYER_ID) instanceof String playerId)) {
            throw new IllegalStateException("A session opened without a ticket"); // Spring closes it
        }
        var client = new Client(new Connection(playerId, session, codec), new MessageRateLimiter(nanoTime));
        clients.put(session.getId(), client);
        client.helloTimer = scheduler.schedule(
                () -> {
                    if (!client.greeted) {
                        client.connection.closeAfterSending(NO_HELLO);
                    }
                },
                helloTimeout.toMillis(),
                TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO, "Player {0} connected", playerId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client client = clients.get(session.getId());
        if (client == null || client.connection.isClosing()) {
            return; // nothing more is read from a connection on its way out
        }
        // Before reading: a flood of bad frames is limited too, and with it the errors it causes.
        if (!client.messages.tryAcquire()) {
            client.connection.closeAfterSending(TOO_MANY_MESSAGES);
            return;
        }
        switch (codec.read(message.getPayload())) {
            case Incoming.Invalid invalid -> refuse(client, invalid.seq(), invalid.reason());
            case Incoming.Message valid -> handle(client, valid);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client client = clients.remove(session.getId());
        if (client == null) {
            return;
        }
        String playerId = client.connection.playerId();
        client.connection.stop();
        client.cancelHelloTimer();
        if (client.greeted) { // Before hello the player never entered the room.
            broadcaster.remove(playerId);
            room.accept(new Command.Leave(playerId));
        }
        LOG.log(System.Logger.Level.INFO, "Player {0} disconnected: {1}", playerId, status);
    }

    private void handle(Client client, Incoming.Message message) {
        // No default: a new client message does not compile until it is handled here.
        switch (message.payload()) {
            case HelloPayload hello -> hello(client, message.seq(), hello);
            case MoveToPayload move -> moveTo(client, message.seq(), move);
        }
    }

    private void hello(Client client, int seq, HelloPayload hello) {
        if (client.greeted) {
            refuse(client, seq, "hello was already sent");
            return;
        }
        int version = hello.protocolVersion();
        if (version < ProtocolVersion.MIN_SUPPORTED || version > ProtocolVersion.CURRENT) {
            String supported = "the server speaks " + ProtocolVersion.MIN_SUPPORTED + " to " + ProtocolVersion.CURRENT;
            send(client, ErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version: " + supported, seq);
            client.connection.closeAfterSending(UNSUPPORTED_VERSION);
            return;
        }
        client.greeted = true;
        client.cancelHelloTimer();
        String playerId = client.connection.playerId();
        // welcome is queued before the room hears of the player, so it always comes before roomState.
        client.connection.send(List.of(new WelcomePayload(playerId)));
        broadcaster.add(client.connection);
        room.accept(new Command.Join(playerId));
    }

    private void moveTo(Client client, int seq, MoveToPayload move) {
        if (!client.greeted) {
            refuse(client, seq, "Send hello first");
            return;
        }
        // No answer from here: the room answers on its next tick, with a path or an error.
        room.accept(Translator.toCommand(client.connection.playerId(), seq, move));
    }

    /** An invalidMessage error. The connection stays open: a client with a bug can go on. */
    private void refuse(Client client, @Nullable Integer seq, String reason) {
        send(client, ErrorCode.INVALID_MESSAGE, reason, seq);
    }

    private static void send(Client client, ErrorCode code, String message, @Nullable Integer refSeq) {
        client.connection.send(List.of(new ErrorPayload(code, message, refSeq)));
    }

    /** One session's state. Its messages arrive one at a time, but not always on the same thread. */
    private static final class Client {
        final Connection connection;
        final MessageRateLimiter messages;
        volatile boolean greeted;
        volatile @Nullable ScheduledFuture<?> helloTimer;

        Client(Connection connection, MessageRateLimiter messages) {
            this.connection = connection;
            this.messages = messages;
        }

        void cancelHelloTimer() {
            ScheduledFuture<?> timer = helloTimer;
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }
}
