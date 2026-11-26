// Checks the schemas themselves with example messages, using Ajv in strict mode.
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, test } from "node:test";
import { Ajv2020 } from "ajv/dist/2020.js";

const ajv = new Ajv2020({ strict: true });
// x-message is our own keyword: strict mode refuses it unless it is registered.
ajv.addKeyword("x-message");
for (const file of readdirSync(import.meta.dirname)) {
  if (file.endsWith(".schema.json")) {
    ajv.addSchema(
      JSON.parse(readFileSync(join(import.meta.dirname, file), "utf8")),
    );
  }
}

const tile = { x: 1, y: 2 };
const longText = (length: number): string => "a".repeat(length);

/** Valid and invalid examples, by schema file. */
const examples: Record<string, { valid: unknown[]; invalid: unknown[] }> = {
  "envelope.schema.json": {
    valid: [{ type: "moveTo", seq: 1, payload: {} }],
    invalid: [
      { type: "move_to", seq: 1, payload: {} },
      { type: "MoveTo", seq: 1, payload: {} },
      { type: "moveTo", seq: 0, payload: {} },
      { type: "moveTo", seq: 1 },
      { type: "moveTo", seq: 1, payload: {}, extra: true },
    ],
  },
  "hello.schema.json": {
    valid: [
      { protocolVersion: 1 },
      { protocolVersion: 7, clientVersion: "0.1.0" },
    ],
    invalid: [
      { protocolVersion: 0 },
      { protocolVersion: "1" },
      {},
      { protocolVersion: 1, clientVersion: "" },
      { protocolVersion: 1, clientVersion: longText(33) },
    ],
  },
  "move-to.schema.json": {
    valid: [
      { x: 0, y: 0 },
      { x: 4, y: 7 },
    ],
    invalid: [
      { x: -1, y: 0 },
      { x: 1.5, y: 0 },
      { x: 1 },
      { x: 1, y: 2, z: 3 },
    ],
  },
  "path.schema.json": {
    valid: [{ playerId: "p-1", tiles: [tile] }],
    invalid: [
      { playerId: "p-1", tiles: [] },
      { playerId: "", tiles: [tile] },
    ],
  },
  "room-state.schema.json": {
    valid: [
      {
        roomId: "lobby",
        width: 10,
        height: 8,
        blocked: [tile],
        players: [
          { playerId: "p-1", position: tile },
          { playerId: "p-2", position: tile, path: [{ x: 2, y: 2 }] },
        ],
      },
    ],
    invalid: [
      { roomId: "lobby", width: 0, height: 8, blocked: [], players: [] },
      {
        roomId: "lobby",
        width: 10,
        height: 8,
        blocked: [],
        players: [{ playerId: "p-1" }],
      },
    ],
  },
  "error.schema.json": {
    valid: [
      { code: "unsupportedProtocolVersion", message: "Update the client" },
      { code: "invalidMessage", message: "Bad frame", refSeq: 3 },
      { code: "invalidTile", message: "Tile is blocked" },
      { code: "rateLimited", message: "Too many moves", refSeq: 12 },
    ],
    invalid: [
      { code: "notACode", message: "x" },
      { code: "invalidTile", message: "" },
      { code: "invalidTile", message: longText(201) },
    ],
  },
  "welcome.schema.json": { valid: [{ playerId: "p-1" }], invalid: [{}] },
  "player-joined.schema.json": {
    valid: [{ player: { playerId: "p-1", position: tile } }],
    invalid: [{ player: { position: tile } }],
  },
  "player-left.schema.json": { valid: [{ playerId: "p-1" }], invalid: [{}] },
};

for (const [file, { valid, invalid }] of Object.entries(examples)) {
  describe(file, () => {
    const validate = ajv.getSchema(file);

    for (const example of valid) {
      test(`accepts ${JSON.stringify(example)}`, () => {
        assert.ok(validate, `${file} is not loaded`);
        assert.equal(validate(example), true, JSON.stringify(validate.errors));
      });
    }
    for (const example of invalid) {
      test(`refuses ${JSON.stringify(example)}`, () => {
        assert.ok(validate, `${file} is not loaded`);
        assert.equal(validate(example), false);
      });
    }
  });
}

test("every message schema has examples", () => {
  const files = readdirSync(import.meta.dirname).filter(
    (file) => file.endsWith(".schema.json") && file !== "common.schema.json",
  );
  assert.deepEqual(files.sort(), Object.keys(examples).sort());
});
