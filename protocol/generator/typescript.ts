// Translates the message schemas into generated/index.ts, with json-schema-to-typescript.
import { compile, type JSONSchema } from "json-schema-to-typescript";
import {
  byDirection,
  NOTICE,
  payloadName,
  SCHEMAS_DIR,
  type Direction,
  type Message,
  type Version,
} from "./generate.ts";

/** An object schema whose properties are the payloads of one direction, keyed by type. */
function directionSchema(
  messages: Message[],
  direction: Direction,
  title: string,
): JSONSchema {
  const own = byDirection(messages, direction);
  const properties: Record<string, JSONSchema> = {};
  for (const message of own) {
    const { "x-message": _meta, ...payload } = message.schema;
    properties[message.type] = { ...payload, title: payloadName(message) };
  }
  return {
    title,
    description: `Messages the ${direction} sends: type -> payload.`,
    type: "object",
    properties,
    required: own.map((message) => message.type),
    additionalProperties: false,
  };
}

/** The parts no payload schema can express: version, envelope, unions and frame. */
function typescriptTail(version: Version): string {
  return `
/** Protocol version this code speaks. */
export const PROTOCOL_VERSION = ${version.current};

/** Oldest protocol version the server still accepts. */
export const MIN_SUPPORTED_PROTOCOL_VERSION = ${version.minSupported};

/** The envelope shared by every message. */
export interface Envelope<T extends string, P> {
  type: T;
  seq: number;
  payload: P;
}

type EnvelopeOf<M> = {
  [T in keyof M & string]: Envelope<T, M[T]>;
}[keyof M & string];

/** Any message the client sends, discriminated by type. */
export type ClientMessage = EnvelopeOf<ClientMessages>;

/** Any message the server sends, discriminated by type. */
export type ServerMessage = EnvelopeOf<ServerMessages>;

/** One WebSocket frame from the server: the events of one tick, in order. */
export type ServerFrame = ServerMessage[];
`;
}

/** The text of generated/index.ts. */
export async function typescriptFile(
  version: Version,
  messages: Message[],
): Promise<string> {
  const root: JSONSchema = {
    title: "Protocol",
    description: "Every message of the protocol, by direction.",
    type: "object",
    properties: {
      client: directionSchema(messages, "client", "ClientMessages"),
      server: directionSchema(messages, "server", "ServerMessages"),
    },
    required: ["client", "server"],
    additionalProperties: false,
  };
  const types = await compile(root, "Protocol", {
    cwd: SCHEMAS_DIR,
    bannerComment: `// ${NOTICE}`,
    additionalProperties: false,
  });
  return types + typescriptTail(version);
}
