import assert from "node:assert/strict";
import { test } from "node:test";
import { readMessages, readVersion } from "./generate.ts";
import { typescriptFile } from "./typescript.ts";

const version = readVersion();
const messages = readMessages();
const output = await typescriptFile(version, messages);

/** The keys of one generated interface, such as ClientMessages. */
function keysOf(name: string): string[] {
  const body = new RegExp(`export interface ${name} \\{([^}]*)\\}`).exec(
    output,
  );
  assert.ok(body, `${name} is missing`);
  return [...body[1].matchAll(/^\s+(\w+):/gm)].map((match) => match[1]);
}

test("the maps hold exactly the types of each direction", () => {
  assert.deepEqual(keysOf("ClientMessages"), ["hello", "moveTo"]);
  assert.deepEqual(keysOf("ServerMessages"), [
    "error",
    "path",
    "playerJoined",
    "playerLeft",
    "roomState",
    "welcome",
  ]);
});

test("shared types are declared once", () => {
  for (const name of ["Tile", "Player"]) {
    const declarations = output.match(
      new RegExp(`export interface ${name} \\{`, "g"),
    );
    assert.equal(declarations?.length, 1, name);
  }
});

test("the version constants come from version.json", () => {
  assert.match(output, new RegExp(`PROTOCOL_VERSION = ${version.current};`));
  assert.match(
    output,
    new RegExp(`MIN_SUPPORTED_PROTOCOL_VERSION = ${version.minSupported};`),
  );
});

test("the output is the same on every run", async () => {
  assert.equal(await typescriptFile(version, messages), output);
});
