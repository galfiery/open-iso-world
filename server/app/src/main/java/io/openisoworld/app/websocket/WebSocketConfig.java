package io.openisoworld.app.websocket;

import io.openisoworld.core.room.RoomLoop;
import io.openisoworld.net.message.MessageCodec;
import io.openisoworld.net.session.GameWebSocketHandler;
import io.openisoworld.net.session.RoomBroadcaster;
import io.openisoworld.net.session.TicketHandshakeInterceptor;
import io.openisoworld.net.ticket.TicketStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** The game's WebSocket on /ws: native Spring WebSocket, no STOMP, no SockJS. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final RoomLoop lobby;
    private final RoomBroadcaster broadcaster;
    private final MessageCodec codec;
    private final TicketStore tickets;

    WebSocketConfig(RoomLoop lobby, RoomBroadcaster broadcaster, MessageCodec codec, TicketStore tickets) {
        this.lobby = lobby;
        this.broadcaster = broadcaster;
        this.codec = codec;
        this.tickets = tickets;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new GameWebSocketHandler(lobby::submit, broadcaster, codec), "/ws")
                .addInterceptors(new TicketHandshakeInterceptor(tickets));
    }
}
