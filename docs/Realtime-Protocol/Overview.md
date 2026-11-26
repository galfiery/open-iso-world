# Realtime Protocol

Every message that travels over the WebSocket shares one envelope and is defined once as JSON Schema (draft 2020-12). The schemas in [`protocol/schemas`](../../protocol/schemas/) are the source of truth; this page summarises them. TypeScript types and Java records are generated from them, so client and server can't drift apart.

## Connecting

```text
POST /api/tickets   ──►  ticket (single use, expires in seconds)
GET  /ws?ticket=…   ──►  ticket consumed, session opened
hello   { protocol_version, client_version? }
welcome { player_id }
room_state { … }
```

The ticket is part of the REST API, not of this protocol. Every player gets one, guests included, so access tokens never appear in URLs or logs.

## Envelope

```json
{ "type": "move_to", "seq": 12, "payload": { "x": 4, "y": 7 } }
```

| Field     | Type               | Meaning                                                                              |
| --------- | ------------------ | ------------------------------------------------------------------------------------ |
| `type`    | string, snake_case | The message type                                                                     |
| `seq`     | integer ≥ 1        | Counts the sender's own messages on this connection; each side keeps its own counter |
| `payload` | object             | The body, as the message's schema defines it                                         |

## Frames

- The client sends one envelope per WebSocket frame.
- The server sends the events of one tick together: each frame is a JSON array of envelopes, in the order they happened. There is no separate batch message.

```json
[
  {
    "type": "player_joined",
    "seq": 7,
    "payload": {
      "player": { "player_id": "p-2", "position": { "x": 1, "y": 1 } }
    }
  },
  {
    "type": "path",
    "seq": 8,
    "payload": {
      "player_id": "p-1",
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

| Type      | Payload                                                                                 | Answer                                                                                             |
| --------- | --------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `hello`   | `protocol_version`* (integer ≥ 1), `client_version` (1 to 32 characters, for logs only) | `welcome` then `room_state`, or `error` `unsupported_protocol_version` and the connection closes   |
| `move_to` | `x`_, `y`_ (a tile)                                                                     | None; everyone in the room gets `path`, or the sender gets `error` `invalid_tile` / `rate_limited` |

### Server to Client

| Type            | Payload                                                         | When                                                                                                  |
| --------------- | --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `welcome`       | `player_id`*                                                    | After a valid `hello`; the receiving player's own id                                                  |
| `room_state`    | `room_id`_, `width`_, `height`_, `blocked`_ (tiles), `players`* | Right after `welcome`: the whole room, the receiving player included                                  |
| `path`          | `player_id`_, `tiles`_ (at least one)                           | A player starts walking; the first tile is the starting one, a single tile means standing still there |
| `player_joined` | `player`*                                                       | A player entered the room                                                                             |
| `player_left`   | `player_id`*                                                    | A player left the room                                                                                |
| `error`         | `code`_, `message`_ (1 to 200 characters), `ref_seq`            | A refusal; `ref_seq` is the `seq` of the client message it answers, when there is one                 |

### Shared Types

| Type      | Shape                                                                                       |
| --------- | ------------------------------------------------------------------------------------------- |
| tile      | `{ x, y }`, integers ≥ 0; the server checks that it lies inside the room and is not blocked |
| player    | `player_id`_, `position`_ (a tile), `path` (the tiles still to walk, when moving)           |
| player_id | An opaque, non-empty string; clients never interpret it                                     |

Coordinates are always grid tiles. Isometric or screen coordinates never appear in the protocol.

### Error Codes

| Code                           | Meaning                                                                     |
| ------------------------------ | --------------------------------------------------------------------------- |
| `unsupported_protocol_version` | The `hello` version is outside the range the server accepts                 |
| `invalid_message`              | The frame or its payload does not match the schemas, or the type is unknown |
| `invalid_tile`                 | The tile is outside the room or blocked                                     |
| `rate_limited`                 | Too many commands; about 5 moves per second are allowed                     |

Clients act on `code`; `message` is a short English text for humans and logs.

## Schemas

Each file in `protocol/schemas` describes the **payload** of one message, except `envelope.schema.json` (the envelope) and `common.schema.json` (the shared types). A metadata block at the top says which message it is and who sends it:

```json
"x-message": { "type": "move_to", "direction": "client" }
```

The server validates in two steps: the frame against the envelope, then the payload against the schema whose `x-message.type` matches. All schemas are closed (`additionalProperties: false`), so an unexpected field is refused.

`x-message` is a custom keyword: a strict validator such as Ajv must register it before loading the schemas.

## Versioning

`protocol/version.json` holds `current` (1) and `min_supported` (1). The server accepts a `hello` whose `protocol_version` lies between the two.

- A breaking change bumps `current`: removing or renaming a field or a message, or making a field required.
- Adding a message or an optional field is not breaking.
- `min_supported` goes up when the server stops handling an old version.

## Adding a Message

1. Add or change the schema in `protocol/schemas`, with an `x-message` block, a `title` in PascalCase and a `description`.
2. Reuse the types of `common.schema.json` instead of redefining them.
3. Bump `protocol/version.json` if the change breaks older clients.
4. Regenerate the code and implement both sides in the same change, with tests. Never edit generated code by hand.

## Related

- [Architecture Overview](../Architecture/Overview.md)
