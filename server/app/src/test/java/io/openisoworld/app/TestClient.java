package io.openisoworld.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A game client for tests, on the JDK's own HTTP client: it asks for a ticket, opens the
 * WebSocket and reads what the server sends, as any other client would.
 */
final class TestClient implements AutoCloseable {

    static final Duration WAIT = Duration.ofSeconds(2);

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final WebSocket socket;
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>(); // envelopes
    private final List<Integer> seqs = new CopyOnWriteArrayList<>(); // of every message received
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    private int nextSeq = 1;

    private TestClient(URI uri, @Nullable String origin) {
        WebSocket.Builder builder = HTTP.newWebSocketBuilder().connectTimeout(WAIT);
        if (origin != null) {
            builder.header("Origin", origin); // what a browser sends; other clients send none
        }
        this.socket = builder.buildAsync(uri, new Listener())
                .orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }

    /** POST /api/tickets. */
    static HttpResponse<String> requestTicket(int port) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tickets"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String ticket(int port) throws Exception {
        return JSON.readTree(requestTicket(port).body()).get("ticket").asString();
    }

    /** Opens the WebSocket with this ticket; fails with the handshake's error if it is refused. */
    static TestClient open(int port, String ticket) {
        return open(port, ticket, null);
    }

    /** Opens the WebSocket as a page from this origin would. */
    static TestClient open(int port, String ticket, @Nullable String origin) {
        return new TestClient(URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket), origin);
    }

    /** A new guest with a fresh ticket, connected but not greeted yet. */
    static TestClient connect(int port) throws Exception {
        return open(port, ticket(port));
    }

    /** A new guest who said hello and received welcome and roomState. Returns the client. */
    static TestClient enter(int port) throws Exception {
        TestClient client = connect(port);
        client.send("hello", "{\"protocolVersion\":1}");
        client.await("welcome");
        client.await("roomState");
        return client;
    }

    /** Sends one envelope with the next seq, and returns that seq. */
    int send(String type, String payloadJson) {
        int seq = nextSeq++;
        sendRaw("{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"payload\":" + payloadJson + "}");
        return seq;
    }

    void sendRaw(String frame) {
        socket.sendText(frame, true).join();
    }

    /** The next message of this type, skipping the others. Fails after two seconds. */
    JsonNode await(String type) throws InterruptedException {
        return await(type, payload -> true);
    }

    /** The next message of this type whose payload matches, skipping the others. */
    JsonNode await(String type, Predicate<JsonNode> payload) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            JsonNode envelope = received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (envelope == null) {
                throw new AssertionError("No " + type + " received");
            }
            if (envelope.get("type").asString().equals(type) && payload.test(envelope.get("payload"))) {
                return envelope;
            }
        }
    }

    /** True if no message of this type arrives within a short time. */
    boolean receivesNo(String type) throws InterruptedException {
        try {
            await(type);
            return false;
        } catch (AssertionError e) {
            return true;
        }
    }

    /** The close code the server sent. Fails after two seconds. */
    int awaitClose() {
        return closeCode.orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    List<Integer> seqs() {
        return List.copyOf(seqs);
    }

    static String playerId(JsonNode welcome) {
        return welcome.get("payload").get("playerId").asString();
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null).join();
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                for (JsonNode envelope : JSON.readTree(partial.toString())) { // every server frame is an array
                    seqs.add(envelope.get("seq").asInt());
                    received.add(envelope);
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }
}
