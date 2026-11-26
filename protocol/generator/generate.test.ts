import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, test } from "node:test";
import {
  byDirection,
  compareDirectory,
  kebabCase,
  payloadName,
  toMessage,
  type Schema,
} from "./generate.ts";

const helloSchema: Schema = {
  "x-message": { type: "hello", direction: "client" },
  title: "Hello",
};

describe("toMessage", () => {
  test("accepts a well-formed message schema", () => {
    const message = toMessage("hello.schema.json", helloSchema);

    assert.equal(message.type, "hello");
    assert.equal(message.direction, "client");
  });

  const invalid: [string, string, Schema, RegExp][] = [
    [
      "no x-message",
      "hello.schema.json",
      { title: "Hello" },
      /missing "x-message"/,
    ],
    [
      "a snake_case type",
      "move_to.schema.json",
      {
        "x-message": { type: "move_to", direction: "client" },
        title: "MoveTo",
      },
      /not camelCase/,
    ],
    [
      "a file not named after the type",
      "moveTo.schema.json",
      { "x-message": { type: "moveTo", direction: "client" }, title: "MoveTo" },
      /expected the file name move-to\.schema\.json/,
    ],
    [
      "an unknown direction",
      "hello.schema.json",
      {
        ...helloSchema,
        "x-message": { type: "hello", direction: "both" as "client" },
      },
      /direction must be/,
    ],
    [
      "a title that is not PascalCase",
      "hello.schema.json",
      { ...helloSchema, title: "hello" },
      /title must be PascalCase/,
    ],
  ];
  for (const [name, file, schema, error] of invalid) {
    test(`refuses ${name}`, () => {
      assert.throws(() => toMessage(file, schema), error);
    });
  }
});

test("kebabCase turns a camelCase type into a file name", () => {
  assert.equal(kebabCase("playerJoined"), "player-joined");
  assert.equal(
    kebabCase("unsupportedProtocolVersion"),
    "unsupported-protocol-version",
  );
  assert.equal(kebabCase("hello"), "hello");
});

test("payloadName adds the Payload suffix to the title", () => {
  assert.equal(
    payloadName(toMessage("hello.schema.json", helloSchema)),
    "HelloPayload",
  );
});

test("byDirection keeps the messages of one direction", () => {
  const hello = toMessage("hello.schema.json", helloSchema);
  const welcome = toMessage("welcome.schema.json", {
    "x-message": { type: "welcome", direction: "server" },
    title: "Welcome",
  });

  assert.deepEqual(byDirection([hello, welcome], "server"), [welcome]);
});

describe("compareDirectory", () => {
  const withDirectory = (check: (dir: string) => void): void => {
    const dir = mkdtempSync(join(tmpdir(), "protocol-check-"));
    try {
      check(dir);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  };
  const problemsIn = (dir: string, files: Map<string, string>): string[] =>
    compareDirectory(dir, files).map((problem) => problem.split(":")[0]);

  test("finds nothing when the files match", () => {
    withDirectory((dir) => {
      writeFileSync(join(dir, "A.java"), "a\n");

      assert.deepEqual(compareDirectory(dir, new Map([["A.java", "a\n"]])), []);
    });
  });

  test("ignores CRLF line endings on disk", () => {
    withDirectory((dir) => {
      writeFileSync(join(dir, "A.java"), "a\r\nb\r\n");

      assert.deepEqual(
        compareDirectory(dir, new Map([["A.java", "a\nb\n"]])),
        [],
      );
    });
  });

  test("reports changed, missing and no longer generated files", () => {
    withDirectory((dir) => {
      writeFileSync(join(dir, "A.java"), "old\n");
      writeFileSync(join(dir, "Old.java"), "old\n");
      const files = new Map([
        ["A.java", "new\n"],
        ["B.java", "new\n"],
      ]);

      assert.deepEqual(problemsIn(dir, files), [
        "changed",
        "missing",
        "not generated",
      ]);
    });
  });

  test("reports every file of a directory that does not exist", () => {
    const dir = join(tmpdir(), "protocol-check-does-not-exist");

    assert.deepEqual(problemsIn(dir, new Map([["A.java", "a\n"]])), [
      "missing",
    ]);
  });
});
