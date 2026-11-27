package io.openisoworld.net.message;

import io.openisoworld.net.message.Incoming.Invalid;
import io.openisoworld.net.message.Incoming.Message;
import io.openisoworld.net.protocol.ClientPayload;
import io.openisoworld.net.protocol.Envelope;
import io.openisoworld.net.protocol.MessageTypes;
import io.openisoworld.net.protocol.ServerPayload;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads client frames and writes server frames, exactly as protocol/schemas describes them. */
public final class MessageCodec {

    /**
     * As strict as the schemas, which are closed. Jackson is lenient by default, and every
     * leniency would let through something the schemas refuse. Set explicitly even where Jackson 3
     * already agrees, so that the rules can be read here.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // {"x":1,"y":2,"z":3}
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES) // {"x":null,"y":2} read as x = 0
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT) // {"x":1.7,"y":2} read as x = 1
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS) // {"x":"1","y":2}
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION) // {"x":1,"x":2,"y":2}: the last one wins
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) // {...} {...} in one frame
            .build();

    /** The mapper of the WebSocket, for tests that check the protocol records with it. */
    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** One envelope, as the client sends it. Never throws: a bad frame is an {@link Invalid}. */
    public Incoming read(String frame) {
        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(frame);
        } catch (JacksonException e) {
            return new Invalid(null, "The frame is not valid JSON");
        }
        if (!envelope.isObject()) {
            return new Invalid(null, "A frame holds one envelope object");
        }
        // Read first: even if the rest is wrong, the error can then point back to this message.
        Integer seq = seqOf(envelope.get("seq"));
        if (seq == null) {
            return new Invalid(null, "seq must be an integer of at least 1");
        }
        return readEnvelope(envelope, seq);
    }

    private static Incoming readEnvelope(JsonNode envelope, int seq) {
        JsonNode type = envelope.get("type");
        JsonNode payload = envelope.get("payload");
        if (envelope.size() != 3 || type == null || payload == null) {
            return new Invalid(seq, "An envelope holds type, seq and payload, and nothing else");
        }
        if (!type.isString()) {
            return new Invalid(seq, "type must be a string");
        }
        if (!payload.isObject()) {
            return new Invalid(seq, "payload must be an object");
        }
        Class<? extends ClientPayload> payloadClass = MessageTypes.CLIENT.get(type.asString());
        if (payloadClass == null) {
            return new Invalid(seq, "Unknown message type"); // server types too: clients do not send them
        }
        try {
            return new Message(seq, MAPPER.treeToValue(payload, payloadClass));
        } catch (JacksonException e) {
            // The type is one of ours, so the reason still holds no text from the client.
            return new Invalid(seq, "Invalid payload for " + type.asString());
        }
    }

    private static @Nullable Integer seqOf(@Nullable JsonNode seq) {
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToInt() || seq.intValue() < 1) {
            return null;
        }
        return seq.intValue();
    }

    /** One server frame: always a JSON array of envelopes, in order, even with only one. */
    public String write(List<Envelope<ServerPayload>> envelopes) {
        return MAPPER.writeValueAsString(envelopes);
    }
}
