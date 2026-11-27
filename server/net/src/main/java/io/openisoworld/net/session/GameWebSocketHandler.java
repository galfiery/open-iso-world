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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 */
public final class GameWebSocketHandler extends TextWebSocketHandler {

    static final CloseStatus UNSUPPORTED_VERSION =
            CloseStatus.PROTOCOL_ERROR.withReason("Unsupported protocol version");

    private static final System.Logger LOG = System.getLogger(GameWebSocketHandler.class.getName());

    private final Consumer<Command> room;
    private final RoomBroadcaster broadcaster;
    private final MessageCodec codec;
    private final Map<String, Client> clients = new ConcurrentHashMap<>(); // by session id

    /** {@code room} takes commands for the room, for example {@code roomLoop::submit}. */
    public GameWebSocketHandler(Consumer<Command> room, RoomBroadcaster broadcaster, MessageCodec codec) {
        this.room = room;
        this.broadcaster = broadcaster;
        this.codec = codec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!(session.getAttributes().get(TicketHandshakeInterceptor.PLAYER_ID) instanceof String playerId)) {
            throw new IllegalStateException("A session opened without a ticket"); // Spring closes it
        }
        clients.put(session.getId(), new Client(new Connection(playerId, session, codec)));
        LOG.log(System.Logger.Level.INFO, "Player {0} connected", playerId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client client = clients.get(session.getId());
        if (client == null) {
            return; // closing
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
        volatile boolean greeted;

        Client(Connection connection) {
            this.connection = connection;
        }
    }
}
