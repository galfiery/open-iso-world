package io.openisoworld.net.message;

import io.openisoworld.net.protocol.ClientPayload;
import org.jspecify.annotations.Nullable;

/** One frame from a client: a message the server understands, or the reason it does not. */
public sealed interface Incoming {

    /** A valid envelope with its payload. */
    record Message(int seq, ClientPayload payload) implements Incoming {}

    /**
     * A frame the server refuses. {@code seq} is there when the frame had a valid one, so the error
     * can point back to it. {@code reason} is written by the server, never copied from the frame.
     */
    record Invalid(@Nullable Integer seq, String reason) implements Incoming {}
}
