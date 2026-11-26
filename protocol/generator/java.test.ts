import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  readCommonTypes,
  readMessages,
  readVersion,
  toMessage,
  type Schema,
} from "./generate.ts";
import { javaFiles } from "./java.ts";

const version = readVersion();
const common = readCommonTypes();

/** The Java files of a single, made-up server message whose payload is the given schema. */
function filesFor(payload: Schema): Map<string, string> {
  const schema = {
    "x-message": { type: "sample", direction: "server" },
    title: "Sample",
    ...payload,
  };
  return javaFiles(
    version,
    [toMessage("sample.schema.json", schema as Schema)],
    common,
  );
}

function sampleRecord(payload: Schema): string {
  const record = filesFor(payload).get("SamplePayload.java");
  assert.ok(record, "SamplePayload.java is missing");
  return record;
}

describe("rules of the translator", () => {
  test("stops on a keyword it does not know, naming the field", () => {
    const payload = {
      type: "object",
      properties: { name: { type: "string", pattern: "^a" } },
    };

    assert.throws(() => filesFor(payload), /SamplePayload\.name: .*"pattern"/);
  });

  test("stops on objects that allow additional properties", () => {
    const payload = {
      type: "object",
      properties: {},
      additionalProperties: true,
    };

    assert.throws(
      () => filesFor(payload),
      /only "additionalProperties": false/,
    );
  });

  test("turns an enum into constants that keep the value of the protocol", () => {
    const payload = {
      type: "object",
      required: ["code"],
      properties: {
        code: { type: "string", enum: ["invalidTile", "rateLimited"] },
      },
    };
    const enumFile = filesFor(payload).get("SampleCode.java") ?? "";

    assert.match(enumFile, /@JsonProperty\("invalidTile"\)\s+INVALID_TILE/);
    assert.match(enumFile, /@JsonProperty\("rateLimited"\)\s+RATE_LIMITED/);
  });

  test("makes an optional integer an Integer and checks it only when present", () => {
    const record = sampleRecord({
      type: "object",
      properties: { count: { type: "integer", minimum: 1 } },
    });

    assert.match(record, /Integer count/);
    assert.match(record, /if \(count != null && count < 1\)/);
    assert.match(record, /@JsonInclude\(JsonInclude\.Include\.NON_NULL\)/);
  });

  test("requires required values and copies lists", () => {
    const record = sampleRecord({
      type: "object",
      required: ["tiles"],
      properties: {
        tiles: {
          type: "array",
          items: { $ref: "common.schema.json#/$defs/tile" },
        },
      },
    });

    assert.match(record, /@JsonProperty\(required = true\) List<Tile> tiles/);
    assert.match(record, /Objects\.requireNonNull\(tiles, "tiles"\);/);
    assert.match(record, /tiles = List\.copyOf\(tiles\);/);
  });
});

describe("the real schemas", () => {
  const messages = readMessages();
  const files = javaFiles(version, messages, common);

  test("give the expected files, in alphabetical order", () => {
    assert.deepEqual(
      [...files.keys()],
      [
        "ClientPayload.java",
        "Envelope.java",
        "ErrorCode.java",
        "ErrorPayload.java",
        "HelloPayload.java",
        "MessageTypes.java",
        "MoveToPayload.java",
        "PathPayload.java",
        "Player.java",
        "PlayerJoinedPayload.java",
        "PlayerLeftPayload.java",
        "ProtocolVersion.java",
        "RoomStatePayload.java",
        "ServerPayload.java",
        "Tile.java",
        "WelcomePayload.java",
      ],
    );
  });

  test("give the same output on every run", () => {
    assert.deepEqual(javaFiles(version, messages, common), files);
  });
});
