package io.openisoworld.core.room;

import java.util.List;

/** Receives the events of a room, one batch per tick. Implemented by the transport. */
public interface RoomListener {

    /**
     * Called on the room's thread after each tick that produced events, with all of them in order.
     * It must return quickly: the room cannot tick again until it does.
     */
    void onTick(String roomId, List<Outgoing> events);
}
