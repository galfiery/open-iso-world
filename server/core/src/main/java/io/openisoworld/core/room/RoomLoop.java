package io.openisoworld.core.room;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Runs a room: queues commands from any thread and ticks the room about ten times a second.
 *
 * <p>Only {@link #tick} touches the room, and the scheduler never runs two ticks of the same loop
 * at once, even with several threads. Each tick also sees everything the previous one wrote. So
 * the room needs no locks: the queue is the only thing shared between threads.
 */
public final class RoomLoop implements AutoCloseable {

    public static final Duration TICK = Duration.ofMillis(100);

    private static final System.Logger LOG = System.getLogger(RoomLoop.class.getName());

    private final Room room;
    private final RoomListener listener;
    private final Queue<Command> commands = new ConcurrentLinkedQueue<>();
    private @Nullable ScheduledFuture<?> ticking;
    private volatile boolean closed;

    public RoomLoop(Room room, RoomListener listener) {
        this.room = room;
        this.listener = listener;
    }

    /** Queues a command for the next tick. Safe to call from any thread. */
    public void submit(Command command) {
        commands.add(command);
    }

    /**
     * Starts ticking on the given scheduler. The scheduler belongs to the caller, who shuts it
     * down; one scheduler can run many rooms.
     */
    public synchronized void start(ScheduledExecutorService scheduler) {
        if (ticking != null || closed) {
            throw new IllegalStateException("Room " + room.id() + " was already started");
        }
        long period = TICK.toMillis();
        // At a fixed rate, not with a fixed delay: the time a tick takes does not slow the room down.
        ticking = scheduler.scheduleAtFixedRate(this::tick, period, period, TimeUnit.MILLISECONDS);
    }

    /** Stops ticking. Commands still in the queue are dropped. */
    @Override
    public synchronized void close() {
        closed = true;
        if (ticking != null) {
            ticking.cancel(false);
        }
        commands.clear();
    }

    /** One tick: drain the queue, tick the room, hand the events to the listener. */
    void tick() {
        if (closed) {
            return;
        }
        // If a periodic task throws, the scheduler silently never runs it again: the room would
        // freeze. So a failing tick is logged, and the next one runs as usual.
        try {
            List<Outgoing> events = room.tick(drain());
            if (!events.isEmpty()) {
                listener.onTick(room.id(), events);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "A tick of room " + room.id() + " failed", e);
        }
    }

    private List<Command> drain() {
        List<Command> drained = new ArrayList<>();
        for (Command command = commands.poll(); command != null; command = commands.poll()) {
            drained.add(command);
        }
        return drained;
    }
}
