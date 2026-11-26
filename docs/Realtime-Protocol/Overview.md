# Realtime Protocol

Every message that travels over the WebSocket shares one envelope and is defined once as JSON Schema (draft 2020-12). The schemas in [`protocol/schemas`](../../protocol/schemas/) are the source of truth; this page summarises them. TypeScript types and Java records are generated from them, so client and server can't drift apart.

## Connecting

```text
POST /api/tickets   ──►  ticket (single use, expires in seconds)
GET  /ws?ticket=…   ──►  ticket consumed, session opened
hello   { protocolVersion, clientVersion? }
welcome { playerId }
roomState { … }
```

The ticket is part of the REST API, not of this protocol. Every player gets one, guests included, so access tokens never appear in URLs or logs.

## Envelope

```json
{ "type": "moveTo", "seq": 12, "payload": { "x": 4, "y": 7 } }
```

| Field     | Type              | Meaning                                                                              |
| --------- | ----------------- | ------------------------------------------------------------------------------------ |
| `type`    | string, camelCase | The message type                                                                     |
| `seq`     | integer ≥ 1       | Counts the sender's own messages on this connection; each side keeps its own counter |
| `payload` | object            | The body, as the message's schema defines it                                         |

Every name in the protocol is camelCase: message types (`moveTo`), field names (`playerId`) and enum values such as error codes (`invalidTile`). It is the convention of TypeScript and Java, so a field has the same name in the schema, in the JSON and in the generated code.

## Frames

- The client sends one envelope per WebSocket frame.
- The server sends the events of one tick together: each frame is a JSON array of envelopes, in the order they happened. There is no separate batch message.

```json
[
  {
    "type": "playerJoined",
    "seq": 7,
    "payload": {
      "player": { "playerId": "p-2", "position": { "x": 1, "y": 1 } }
    }
  },
  {
    "type": "path",
    "seq": 8,
    "payload": {
      "playerId": "p-1",
      "tiles": [
        { "x": 3, "y": 7 },
        { "x": 4, "y": 7 }
      ]
    }
  }
]
```

## Messages

Fields marked * are required.

### Client to Server

| Type     | Payload                                                                               | Answer                                                                                           |
| -------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `hello`  | `protocolVersion`* (integer ≥ 1), `clientVersion` (1 to 32 characters, for logs only) | `welcome` then `roomState`, or `error` `unsupportedProtocolVersion` and the connection closes    |
| `moveTo` | `x`_, `y`_ (a tile)                                                                   | None; everyone in the room gets `path`, or the sender gets `error` `invalidTile` / `rateLimited` |

### Server to Client

| Type           | Payload                                                        | When                                                                                                  |
| -------------- | -------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `welcome`      | `playerId`*                                                    | After a valid `hello`; the receiving player's own id                                                  |
| `roomState`    | `roomId`_, `width`_, `height`_, `blocked`_ (tiles), `players`* | Right after `welcome`: the whole room, the receiving player included                                  |
| `path`         | `playerId`_, `tiles`_ (at least one)                           | A player starts walking; the first tile is the starting one, a single tile means standing still there |
| `playerJoined` | `player`*                                                      | A player entered the room                                                                             |
| `playerLeft`   | `playerId`*                                                    | A player left the room                                                                                |
| `error`        | `code`_, `message`_ (1 to 200 characters), `refSeq`            | A refusal; `refSeq` is the `seq` of the client message it answers, when there is one                  |

### Shared Types

| Type     | Shape                                                                                       |
| -------- | ------------------------------------------------------------------------------------------- |
| tile     | `{ x, y }`, integers ≥ 0; the server checks that it lies inside the room and is not blocked |
| player   | `playerId`_, `position`_ (a tile), `path` (the tiles still to walk, when moving)            |
| playerId | An opaque, non-empty string; clients never interpret it                                     |

Coordinates are always grid tiles. Isometric or screen coordinates never appear in the protocol.

### Error Codes

| Code                         | Meaning                                                                     |
| ---------------------------- | --------------------------------------------------------------------------- |
| `unsupportedProtocolVersion` | The `hello` version is outside the range the server accepts                 |
| `invalidMessage`             | The frame or its payload does not match the schemas, or the type is unknown |
| `invalidTile`                | The tile is outside the room or blocked                                     |
| `rateLimited`                | Too many commands; about 5 moves per second are allowed                     |

Clients act on `code`; `message` is a short English text for humans and logs.

## Schemas

Each file in `protocol/schemas` describes the **payload** of one message, except `envelope.schema.json` (the envelope) and `common.schema.json` (the shared types). A metadata block at the top says which message it is and who sends it:

```json
"x-message": { "type": "moveTo", "direction": "client" }
```

The server validates in two steps: the frame against the envelope, then the payload against the schema whose `x-message.type` matches. All schemas are closed (`additionalProperties: false`), so an unexpected field is refused.

`x-message` is a custom keyword: a strict validator such as Ajv must register it before loading the schemas.

## Versioning

`protocol/version.json` holds `current` (1) and `minSupported` (1). The server accepts a `hello` whose `protocolVersion` lies between the two.

- A breaking change bumps `current`: removing or renaming a field or a message, or making a field required.
- Adding a message or an optional field is not breaking.
- `minSupported` goes up when the server stops handling an old version.

## Adding a Message

1. Add or change the schema in `protocol/schemas`, named `<type>.schema.json` with the type in camelCase, with an `x-message` block, a `title` in PascalCase and a `description`.
2. Reuse the types of `common.schema.json` instead of redefining them.
3. Bump `protocol/version.json` if the change breaks older clients.
4. Regenerate the code and implement both sides in the same change, with tests. Never edit generated code by hand.

## Related

- [Architecture Overview](../Architecture/Overview.md)
