package io.openisoworld.net.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.openisoworld.net.message.Incoming.Invalid;
import io.openisoworld.net.message.Incoming.Message;
import io.openisoworld.net.protocol.Envelope;
import io.openisoworld.net.protocol.HelloPayload;
import io.openisoworld.net.protocol.MoveToPayload;
import io.openisoworld.net.protocol.PlayerLeftPayload;
import io.openisoworld.net.protocol.ServerPayload;
import io.openisoworld.net.protocol.WelcomePayload;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec();

    /** Lets the JSON of the tests use single quotes: {'x': 1} becomes {"x": 1}. */
    private static String json(String singleQuoted) {
        return singleQuoted.replace('\'', '"');
    }

    private Incoming read(String singleQuoted) {
        return codec.read(json(singleQuoted));
    }

    // Valid frames

    @Test
    void readsHello() {
        assertEquals(
                new Message(1, new HelloPayload(1, "0.1.0")),
                read("{'type':'hello','seq':1,'payload':{'protocolVersion':1,'clientVersion':'0.1.0'}}"));
        assertEquals(
                new Message(1, new HelloPayload(1, null)),
                read("{'type':'hello','seq':1,'payload':{'protocolVersion':1}}"));
    }

    @Test
    void readsMoveTo() {
        assertEquals(
                new Message(12, new MoveToPayload(4, 7)),
                read("{'type':'moveTo','seq':12,'payload':{'x':4,'y':7}}"));
    }

    @Test
    void acceptsTheFieldsOfTheEnvelopeInAnyOrder() {
        assertEquals(
                new Message(3, new MoveToPayload(0, 0)),
                read("{'payload':{'y':0,'x':0},'seq':3,'type':'moveTo'}"));
    }

    // Frames without a valid seq: the error cannot point back to them

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "not json",
        "",
        "[]",
        "[{'type':'moveTo','seq':1,'payload':{'x':1,'y':1}}]",
        "42",
        "'moveTo'",
        "{}",
        "{'type':'moveTo','payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':0,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':-3,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':1.5,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':'1','payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':null,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':3000000000,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':1,'seq':2,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':1,'payload':{'x':1,'y':1}} {}"
    })
    void refusesWithoutSeq(String frame) {
        Invalid invalid = assertInstanceOf(Invalid.class, read(frame));

        assertEquals(null, invalid.seq());
    }

    // Frames with a valid seq: the error carries it as refSeq

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{'seq':5,'payload':{'x':1,'y':1}}",
        "{'type':7,'seq':5,'payload':{'x':1,'y':1}}",
        "{'type':null,'seq':5,'payload':{'x':1,'y':1}}",
        "{'type':'moveTo','seq':5}",
        "{'type':'moveTo','seq':5,'payload':[1,1]}",
        "{'type':'moveTo','seq':5,'payload':{'x':1,'y':1},'extra':true}",
        "{'type':'teleport','seq':5,'payload':{'x':1,'y':1}}",
        "{'type':'path','seq':5,'payload':{'playerId':'p-1','tiles':[{'x':0,'y':0}]}}",
        "{'type':'moveTo','seq':5,'payload':{'x':1,'y':1,'z':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':-1,'y':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':null,'y':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':1.7,'y':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':'1','y':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':true,'y':1}}",
        "{'type':'moveTo','seq':5,'payload':{'x':1,'x':2,'y':1}}",
        "{'type':'hello','seq':5,'payload':{}}",
        "{'type':'hello','seq':5,'payload':{'protocolVersion':0}}"
    })
    void refusesWithSeq(String frame) {
        Invalid invalid = assertInstanceOf(Invalid.class, read(frame));

        // A duplicate key anywhere makes the whole frame unreadable, so it has no seq either.
        Integer expected = frame.contains("'x':1,'x':2") ? null : 5;
        assertEquals(expected, invalid.seq());
    }

    @Test
    void neverCopiesTheFrameIntoTheReason() {
        String longType = "t".repeat(10_000);
        Invalid invalid = assertInstanceOf(
                Invalid.class, read("{'type':'" + longType + "','seq':1,'payload':{}}"));

        assertEquals("Unknown message type", invalid.reason());
    }

    @Test
    void namesTheTypeWhenItsPayloadIsWrong() {
        Invalid invalid = assertInstanceOf(Invalid.class, read("{'type':'moveTo','seq':1,'payload':{}}"));

        assertEquals("Invalid payload for moveTo", invalid.reason());
    }

    // Writing

    @Test
    void writesASingleMessageAsAnArray() {
        List<Envelope<ServerPayload>> frame = List.of(new Envelope<>(WelcomePayload.TYPE, 1, new WelcomePayload("p-1")));

        assertEquals(json("[{'type':'welcome','seq':1,'payload':{'playerId':'p-1'}}]"), codec.write(frame));
    }

    @Test
    void writesSeveralMessagesInOrder() {
        List<Envelope<ServerPayload>> frame = List.of(
                new Envelope<>(PlayerLeftPayload.TYPE, 7, new PlayerLeftPayload("p-2")),
                new Envelope<>(PlayerLeftPayload.TYPE, 8, new PlayerLeftPayload("p-3")));

        assertEquals(
                json("[{'type':'playerLeft','seq':7,'payload':{'playerId':'p-2'}},"
                        + "{'type':'playerLeft','seq':8,'payload':{'playerId':'p-3'}}]"),
                codec.write(frame));
    }
}
