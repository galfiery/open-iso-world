package io.openisoworld.net.session;

import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.protocol.Envelope;
import io.openisoworld.net.protocol.MessageTypes;
import io.openisoworld.net.protocol.ServerPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.jspecify.annotations.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One player's WebSocket. Frames wait in a bounded queue and one virtual thread writes them, so
 * whoever sends (the room's tick, the session's thread) never waits on the network.
 *
 * <p>The writer is the only thread that touches the session, closing included. It also numbers the
 * messages: with a single writer, {@code seq} goes up in order without a lock.
 */
public final class Connection {

    /** At ten ticks a second, 25 seconds of frames. A client this far behind is not reading. */
    static final int QUEUE_CAPACITY = 256;

    static final CloseStatus TOO_SLOW = CloseStatus.POLICY_VIOLATION.withReason("Too slow");

    private final String playerId;
    private final WebSocketSession session;
    private final MessageCodec codec;
    private final BlockingQueue<Item> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread writer;
    private volatile boolean closing; // no more frames are accepted
    private volatile @Nullable CloseStatus abort; // set when the writer must close without draining

    public Connection(String playerId, WebSocketSession session, MessageCodec codec) {
        this.playerId = playerId;
        this.session = session;
        this.codec = codec;
        this.writer = Thread.ofVirtual().name("ws-writer-" + playerId).start(this::write);
    }

    public String playerId() {
        return playerId;
    }

    /** Queues one frame. Never blocks: with the queue full the client is too slow, and it is closed. */
    public void send(List<ServerPayload> payloads) {
        if (closing) {
            return;
        }
        if (!queue.offer(new Item.Frame(payloads))) {
            abort(TOO_SLOW);
        }
    }

    /** Writes what is already queued, then closes the session with this status. */
    public void closeAfterSending(CloseStatus status) {
        if (closing) {
            return;
        }
        closing = true;
        if (!queue.offer(new Item.Close(status))) {
            abort(status);
        }
    }

    /** The session is closed: the writer stops and queued frames are dropped. */
    public void stop() {
        closing = true;
        writer.interrupt();
        queue.clear();
    }

    /** Closes without waiting for the queue: the writer may be stuck on a client that is not reading. */
    private void abort(CloseStatus status) {
        closing = true;
        abort = status;
        writer.interrupt();
    }

    private void write() {
        int seq = 0;
        try {
            while (true) {
                switch (queue.take()) {
                    case Item.Frame frame -> {
                        List<Envelope<ServerPayload>> envelopes = new ArrayList<>();
                        for (ServerPayload payload : frame.payloads()) {
                            envelopes.add(new Envelope<>(MessageTypes.typeOf(payload), ++seq, payload));
                        }
                        session.sendMessage(new TextMessage(codec.write(envelopes)));
                    }
                    case Item.Close close -> {
                        closeSession(close.status());
                        return;
                    }
                }
            }
        } catch (InterruptedException | IOException | RuntimeException e) {
            // Interrupted by abort or stop, or the client is gone: either way this session is over.
            closing = true;
            CloseStatus status = abort;
            closeSession(status != null ? status : CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void closeSession(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            // Already broken: there is nobody left to tell.
        }
    }

    /** What the writer takes from the queue. */
    private sealed interface Item {
        record Frame(List<ServerPayload> payloads) implements Item {}

        record Close(CloseStatus status) implements Item {}
    }
}
