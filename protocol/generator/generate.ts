// Generates TypeScript types and Java records from protocol/schemas.
// Run it with "pnpm protocol:gen"; "pnpm protocol:check" fails when the generated code is stale.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const PROTOCOL_DIR = join(import.meta.dirname, "..");
const SCHEMAS_DIR = join(PROTOCOL_DIR, "schemas");
/** Schemas that are not messages: the shared types and the envelope. */
const NOT_MESSAGES = new Set(["common.schema.json", "envelope.schema.json"]);

export type Direction = "client" | "server";

export interface Schema {
  title?: string;
  "x-message"?: { type: string; direction: Direction };
  [keyword: string]: unknown;
}

export interface Message {
  type: string;
  direction: Direction;
  file: string;
  schema: Schema;
}

export interface Version {
  current: number;
  min_supported: number;
}

function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path, "utf8")) as T;
}

/** Reads version.json and checks that 1 <= min_supported <= current. */
export function readVersion(): Version {
  const version = readJson<Version>(join(PROTOCOL_DIR, "version.json"));
  if (
    !Number.isInteger(version.current) ||
    !Number.isInteger(version.min_supported)
  ) {
    throw new Error("version.json: current and min_supported must be integers");
  }
  if (version.min_supported < 1 || version.min_supported > version.current) {
    throw new Error("version.json: expected 1 <= min_supported <= current");
  }
  return version;
}

/** Checks the x-message block of one schema file and turns it into a Message. */
export function toMessage(file: string, schema: Schema): Message {
  const meta = schema["x-message"];
  if (!meta) {
    throw new Error(`${file}: missing "x-message"`);
  }
  if (!/^[a-z][a-z0-9]*(_[a-z0-9]+)*$/.test(meta.type)) {
    throw new Error(`${file}: type "${meta.type}" is not snake_case`);
  }
  if (file !== `${meta.type}.schema.json`) {
    throw new Error(`${file}: expected the file name ${meta.type}.schema.json`);
  }
  if (meta.direction !== "client" && meta.direction !== "server") {
    throw new Error(`${file}: direction must be "client" or "server"`);
  }
  if (!schema.title || !/^[A-Z][A-Za-z0-9]*$/.test(schema.title)) {
    throw new Error(`${file}: title must be PascalCase`);
  }
  return { type: meta.type, direction: meta.direction, file, schema };
}

/** Every message schema, sorted by file name so that the output is always the same. */
export function readMessages(): Message[] {
  return readdirSync(SCHEMAS_DIR)
    .filter((file) => file.endsWith(".schema.json") && !NOT_MESSAGES.has(file))
    .sort()
    .map((file) => toMessage(file, readJson<Schema>(join(SCHEMAS_DIR, file))));
}

export function byDirection(
  messages: Message[],
  direction: Direction,
): Message[] {
  return messages.filter((message) => message.direction === direction);
}

function main(): void {
  try {
    const version = readVersion();
    const messages = readMessages();
    console.log(
      `Protocol version ${version.current} (min supported ${version.min_supported})`,
    );
    for (const direction of ["client", "server"] as const) {
      const types = byDirection(messages, direction).map(
        (message) => message.type,
      );
      console.log(`${direction}: ${types.join(", ")}`);
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}

// Runs only when the file is executed, not when a test imports it.
if (import.meta.main) {
  main();
}
