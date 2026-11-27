package io.openisoworld.net.session;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** A WebSocket session for tests: it records what is sent and how it was closed. */
class FakeSession implements WebSocketSession {

    private static final Duration WAIT = Duration.ofSeconds(2);

    private final String id;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile CloseStatus closeStatus;
    private volatile CountDownLatch writesBlockedUntil = new CountDownLatch(0);
    private volatile boolean failWrites;

    FakeSession(String id) {
        this.id = id;
    }

    /** The next frame written, waiting for it if needed. Fails after two seconds. */
    String nextFrame() throws InterruptedException {
        String frame = sent.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        if (frame == null) {
            throw new AssertionError("No frame was written to " + id);
        }
        return frame;
    }

    /** True if no frame arrives within a short time. */
    boolean sentNothingMore() throws InterruptedException {
        return sent.poll(100, TimeUnit.MILLISECONDS) == null;
    }

    /** The status the session was closed with, waiting for it. Fails after two seconds. */
    CloseStatus awaitClose() throws InterruptedException {
        if (!closed.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new AssertionError(id + " was not closed");
        }
        return closeStatus;
    }

    /** Makes writes hang, as with a client that does not read; returns the latch that releases them. */
    CountDownLatch blockWrites() {
        writesBlockedUntil = new CountDownLatch(1);
        return writesBlockedUntil;
    }

    void failWrites() {
        failWrites = true;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        try {
            writesBlockedUntil.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing", e);
        }
        if (failWrites) {
            throw new IOException("The client is gone");
        }
        sent.add(((TextMessage) message).getPayload());
    }

    @Override
    public void close(CloseStatus status) {
        if (closed.getCount() > 0) {
            closeStatus = status;
            closed.countDown();
        }
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    @Override
    public boolean isOpen() {
        return closed.getCount() > 0;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    // Not used by the tests

    @Override
    public URI getUri() {
        return URI.create("ws://localhost/ws");
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return new HttpHeaders();
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return null;
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {}

    @Override
    public int getTextMessageSizeLimit() {
        return 0;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {}

    @Override
    public int getBinaryMessageSizeLimit() {
        return 0;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }
}
