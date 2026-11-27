package io.openisoworld.net.session;

import io.openisoworld.core.room.Outgoing;
import io.openisoworld.core.room.Recipients;
import io.openisoworld.core.room.Recipients.Everyone;
import io.openisoworld.core.room.Recipients.EveryoneExcept;
import io.openisoworld.core.room.Recipients.OnlyPlayer;
import io.openisoworld.core.room.RoomEvent.PlayerLeft;
import io.openisoworld.core.room.RoomEvent.RoomSnapshot;
import io.openisoworld.core.room.RoomListener;
import io.openisoworld.net.message.Translator;
import io.openisoworld.net.protocol.ServerPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives each tick's events and gives every player one frame with the events meant for them.
 * Who receives an event is decided by core; this class only routes it.
 *
 * <p>A player receives the events for everyone from their own roomState on. Without this rule, a
 * player whose hello arrives during a tick could get a path before knowing what the room looks like.
 * v0.1 has one room, so one broadcaster; with several rooms the {@code roomId} will choose.
 */
public final class RoomBroadcaster implements RoomListener {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>(); // sessions' threads
    private final Set<String> inRoom = new LinkedHashSet<>(); // only the tick touches it

    /** A player said hello. They receive events from their roomState on. */
    public void add(Connection connection) {
        connections.put(connection.playerId(), connection);
    }

    /** A player's session closed. */
    public void remove(String playerId) {
        connections.remove(playerId);
    }

    @Override
    public void onTick(String roomId, List<Outgoing> events) {
        route(events).forEach((playerId, payloads) -> {
            Connection connection = connections.get(playerId);
            if (connection != null) {
                connection.send(payloads); // never blocks: the tick goes on
            }
        });
    }

    /** The payloads each player receives from this tick, in the tick's order. */
    Map<String, List<ServerPayload>> route(List<Outgoing> events) {
        inRoom.retainAll(connections.keySet()); // players whose session closed since the last tick
        Map<String, List<ServerPayload>> frames = new LinkedHashMap<>();
        for (Outgoing outgoing : events) {
            if (outgoing.event() instanceof PlayerLeft left) {
                inRoom.remove(left.playerId());
            }
            ServerPayload payload = Translator.toPayload(outgoing.event()); // once, shared by all
            for (String playerId : recipients(outgoing.to())) {
                frames.computeIfAbsent(playerId, id -> new ArrayList<>()).add(payload);
            }
            if (outgoing.event() instanceof RoomSnapshot && outgoing.to() instanceof OnlyPlayer(String playerId)
                    && connections.containsKey(playerId)) {
                inRoom.add(playerId);
            }
        }
        return frames;
    }

    private List<String> recipients(Recipients to) {
        return switch (to) {
            case OnlyPlayer(String playerId) -> connections.containsKey(playerId) ? List.of(playerId) : List.of();
            case Everyone() -> List.copyOf(inRoom);
            case EveryoneExcept(String playerId) -> inRoom.stream().filter(id -> !id.equals(playerId)).toList();
        };
    }
}
