package io.openisoworld.net.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.protocol.PlayerLeftPayload;
import io.openisoworld.net.protocol.ServerPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

class ConnectionTest {

    private static final Pattern SEQ = Pattern.compile("\"seq\":(\\d+)");

    private final FakeSession session = new FakeSession("s-1");
    private final Connection connection = new Connection("p-1", session, new MessageCodec());

    @AfterEach
    void stop() {
        connection.stop();
    }

    private static List<ServerPayload> left(String... playerIds) {
        return List.of(playerIds).stream().map(id -> (ServerPayload) new PlayerLeftPayload(id)).toList();
    }

    private static List<Integer> seqs(String frame) {
        List<Integer> seqs = new ArrayList<>();
        Matcher matcher = SEQ.matcher(frame);
        while (matcher.find()) {
            seqs.add(Integer.parseInt(matcher.group(1)));
        }
        return seqs;
    }

    @Test
    void writesFramesInOrderAndNumbersEveryMessage() throws InterruptedException {
        connection.send(left("a"));
        connection.send(left("b", "c"));
        connection.send(left("d"));

        assertEquals(
                "[{\"type\":\"playerLeft\",\"seq\":1,\"payload\":{\"playerId\":\"a\"}}]", session.nextFrame());
        String second = session.nextFrame();
        assertTrue(second.indexOf("\"b\"") < second.indexOf("\"c\""), second);
        assertEquals(List.of(2, 3), seqs(second));
        assertEquals(List.of(4), seqs(session.nextFrame()));
    }

    @Test
    void numbersWithoutGapsWhenTwoThreadsSend() throws Exception {
        int perThread = 100;
        var start = new CountDownLatch(1);
        Runnable sender = () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            for (int i = 0; i < perThread; i++) {
                connection.send(left("x"));
            }
        };
        Thread first = Thread.ofPlatform().start(sender);
        Thread second = Thread.ofPlatform().start(sender);
        start.countDown();
        first.join();
        second.join();

        Set<Integer> seqs = new HashSet<>();
        for (int i = 0; i < 2 * perThread; i++) {
            seqs.addAll(seqs(session.nextFrame()));
        }
        assertEquals(2 * perThread, seqs.size());
        assertTrue(seqs.contains(1) && seqs.contains(2 * perThread));
    }

    @Test
    void neverBlocksAndClosesAClientThatDoesNotRead() throws InterruptedException {
        CountDownLatch release = session.blockWrites();
        connection.send(left("a")); // the writer takes it and hangs on the client

        long start = System.nanoTime();
        for (int i = 0; i <= Connection.QUEUE_CAPACITY + 1; i++) {
            connection.send(left("x"));
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 1_000, "send took " + elapsedMillis + " ms");
        assertEquals(Connection.TOO_SLOW, session.awaitClose());
        release.countDown();
    }

    @Test
    void closesAfterWhatIsAlreadyQueued() throws InterruptedException {
        connection.send(left("a"));
        connection.closeAfterSending(CloseStatus.PROTOCOL_ERROR);
        connection.send(left("too late"));

        assertEquals(List.of(1), seqs(session.nextFrame()));
        assertEquals(CloseStatus.PROTOCOL_ERROR, session.awaitClose());
        assertTrue(session.sentNothingMore());
    }

    @Test
    void closesTheSessionWhenAWriteFails() throws InterruptedException {
        session.failWrites();

        connection.send(left("a"));

        assertEquals(CloseStatus.SESSION_NOT_RELIABLE, session.awaitClose());
    }

    @Test
    void stopsTheWriter() throws InterruptedException {
        connection.stop();
        connection.send(left("a"));

        session.awaitClose();
        assertTrue(session.sentNothingMore());
    }
}
