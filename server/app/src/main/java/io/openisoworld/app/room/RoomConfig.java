package io.openisoworld.app.room;

import io.openisoworld.core.grid.Grid;
import io.openisoworld.core.grid.Tile;
import io.openisoworld.core.room.Room;
import io.openisoworld.core.room.RoomLoop;
import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.session.RoomBroadcaster;
import io.openisoworld.net.ticket.TicketStore;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The room of v0.1 and what it runs on. Spring closes the beans in the reverse order of their
 * dependencies, so on shutdown the room stops before its scheduler.
 */
@Configuration(proxyBeanMethods = false)
class RoomConfig {

    /** The only room of v0.1: {@code .} is floor, {@code #} is blocked. From v0.3 rooms come from the database. */
    static final Grid LOBBY = Grid.parse(
            "..........",
            "..........",
            "..##......",
            "..##...#..",
            ".......#..",
            ".......#..",
            "..........",
            "....###...",
            "..........",
            "..........");

    static final Tile LOBBY_SPAWN = new Tile(1, 1);

    /** One thread is enough for one room, which runs on one thread at a time anyway. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService roomScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("room-tick-", 0).factory());
    }

    @Bean
    MessageCodec messageCodec() {
        return new MessageCodec();
    }

    @Bean
    RoomBroadcaster roomBroadcaster() {
        return new RoomBroadcaster();
    }

    @Bean
    TicketStore ticketStore() {
        return new TicketStore(Clock.systemUTC());
    }

    /** Starts right away: an empty room ticks without producing anything. */
    @Bean(destroyMethod = "close")
    RoomLoop lobby(RoomBroadcaster roomBroadcaster, ScheduledExecutorService roomScheduler) {
        var loop = new RoomLoop(new Room("lobby", LOBBY, LOBBY_SPAWN), roomBroadcaster);
        loop.start(roomScheduler);
        return loop;
    }
}
