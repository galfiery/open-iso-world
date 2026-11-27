package io.openisoworld.core.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openisoworld.core.grid.Grid;
import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Command.Join;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoomLoopTest {

    /** Remembers every call to the listener. */
    private final List<List<Outgoing>> batches = new ArrayList<>();
    private final List<String> roomIds = new ArrayList<>();

    private final RoomLoop loop = new RoomLoop(newRoom(), (roomId, events) -> {
        roomIds.add(roomId);
        batches.add(events);
    });

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopTheScheduler() {
        scheduler.shutdownNow();
    }

    // Ticking by hand

    @Test
    void appliesSubmittedCommandsAtTheNextTick() {
        loop.submit(new Join("p-1"));
        assertTrue(batches.isEmpty());

        loop.tick();

        assertEquals(List.of("lobby"), roomIds);
        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().getFirst().event() instanceof RoomSnapshot);
    }

    @Test
    void commandsFromManyThreadsGoInOneTick() throws InterruptedException {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int thread = t;
            threads.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 25; i++) {
                    loop.submit(new Join("p-" + thread + "-" + i));
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }

        loop.tick();

        assertEquals(1, batches.size());
        long snapshots = batches.getFirst().stream()
                .filter(outgoing -> outgoing.event() instanceof RoomSnapshot)
                .count();
        assertEquals(100, snapshots);
    }

    @Test
    void aTickWithoutEventsDoesNotCallTheListener() {
        loop.tick();

        assertTrue(batches.isEmpty());
    }

    @Test
    void keepsTickingAfterTheListenerFails() {
        List<List<Outgoing>> received = new ArrayList<>();
        var failing = new RoomLoop(newRoom(), (roomId, events) -> {
            received.add(events);
            if (received.size() == 1) {
                throw new IllegalStateException("A listener bug");
            }
        });

        failing.submit(new Join("p-1"));
        failing.tick();
        failing.submit(new Join("p-2"));
        failing.tick();

        assertEquals(2, received.size());
    }

    // Ticking on a scheduler

    @Test
    void ticksOnTheScheduler() throws InterruptedException {
        CountDownLatch snapshot = new CountDownLatch(1);
        var scheduled = new RoomLoop(newRoom(), (roomId, events) -> snapshot.countDown());

        scheduled.start(scheduler);
        scheduled.submit(new Join("p-1"));

        assertTrue(snapshot.await(1, TimeUnit.SECONDS));
        scheduled.close();
    }

    @Test
    void stopsTickingWhenClosed() throws InterruptedException {
        CountDownLatch called = new CountDownLatch(1);
        var scheduled = new RoomLoop(newRoom(), (roomId, events) -> called.countDown());
        scheduled.start(scheduler);

        scheduled.close();
        scheduled.submit(new Join("p-1"));
        scheduled.tick();

        assertFalse(called.await(3 * RoomLoop.TICK.toMillis(), TimeUnit.MILLISECONDS));
    }

    @Test
    void cannotStartTwice() {
        loop.start(scheduler);

        assertThrows(IllegalStateException.class, () -> loop.start(scheduler));
        loop.close();
    }

    private static Room newRoom() {
        return new Room("lobby", Grid.parse("..."), new Tile(0, 0));
    }
}
