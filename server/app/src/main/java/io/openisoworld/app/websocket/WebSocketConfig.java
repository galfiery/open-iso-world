package io.openisoworld.app.websocket;

import io.openisoworld.core.room.RoomLoop;
import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.session.GameWebSocketHandler;
import io.openisoworld.net.session.RoomBroadcaster;
import io.openisoworld.net.session.TicketHandshakeInterceptor;
import io.openisoworld.net.ticket.TicketStore;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

/**
 * The game's WebSocket on /ws: native Spring WebSocket, no STOMP, no SockJS. Only pages from the
 * allowed origins may open it; clients without an Origin header (the bot, tests) are not browsers
 * and are let through, as Spring does.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final RoomLoop lobby;
    private final RoomBroadcaster broadcaster;
    private final MessageCodec codec;
    private final TicketStore tickets;
    private final ScheduledExecutorService roomScheduler;
    private final String[] allowedOrigins;

    WebSocketConfig(
            RoomLoop lobby,
            RoomBroadcaster broadcaster,
            MessageCodec codec,
            TicketStore tickets,
            ScheduledExecutorService roomScheduler,
            @Value("${openisoworld.allowed-origins}") String[] allowedOrigins) {
        this.lobby = lobby;
        this.broadcaster = broadcaster;
        this.codec = codec;
        this.tickets = tickets;
        this.roomScheduler = roomScheduler;
        this.allowedOrigins = allowedOrigins;
    }

    /** The largest frame Tomcat reads. A bigger one closes the connection with 1009. */
    static final int MAX_FRAME_BYTES = 4 * 1024;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var handler = new GameWebSocketHandler(lobby::submit, broadcaster, codec, roomScheduler);
        registry.addHandler(handler, "/ws")
                .setAllowedOrigins(allowedOrigins)
                // Spring runs its own origin check after the interceptors added here. This one runs
                // first, so that a page from another site never burns a ticket.
                .addInterceptors(
                        new OriginHandshakeInterceptor(List.of(allowedOrigins)),
                        new TicketHandshakeInterceptor(tickets));
    }

    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_FRAME_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_FRAME_BYTES); // refused anyway, but never buffered
        return container;
    }
}
