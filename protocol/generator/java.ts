// Translates the message schemas into Java records for the server's net module.
// It knows only the part of JSON Schema the protocol uses, and stops on anything else.
import { join } from "node:path";
import {
  byDirection,
  NOTICE,
  payloadName,
  type Direction,
  type Message,
  type Schema,
  type Version,
} from "./generate.ts";

export const JAVA_PACKAGE = "io.openisoworld.net.protocol";
// Computed from this file's own folder, so the value is ready even though
// generate.ts, which imports this file, has not finished loading yet.
export const JAVA_DIR = join(
  import.meta.dirname,
  "../../server/net/src/main/java",
  ...JAVA_PACKAGE.split("."),
);

/** The keywords the translator understands. */
const KNOWN_KEYWORDS = new Set([
  "$schema",
  "$id",
  "$ref",
  "x-message",
  "title",
  "description",
  "type",
  "properties",
  "required",
  "additionalProperties",
  "items",
  "enum",
  "minimum",
  "minLength",
  "maxLength",
  "minItems",
]);

/** The shared types of common.schema.json, and the Java types found so far, by name. */
interface Context {
  common: Record<string, Schema>;
  types: Map<string, string>;
}

const pascal = (name: string): string =>
  name.charAt(0).toUpperCase() + name.slice(1);

/** invalidTile becomes INVALID_TILE. */
const constantName = (value: string): string =>
  value.replace(/([A-Z])/g, "_$1").toUpperCase();

const plural = (count: number, word: string): string =>
  `${count} ${word}${count === 1 ? "" : "s"}`;

function checkKeywords(schema: Schema, where: string): void {
  for (const keyword of Object.keys(schema)) {
    if (!KNOWN_KEYWORDS.has(keyword)) {
      throw new Error(
        `${where}: the Java generator does not support "${keyword}"`,
      );
    }
  }
  if (
    "additionalProperties" in schema &&
    schema.additionalProperties !== false
  ) {
    throw new Error(
      `${where}: only "additionalProperties": false is supported`,
    );
  }
}

/** Follows a $ref to a shared type and returns its name and schema. */
function resolveRef(
  ref: string,
  context: Context,
): { name: string; schema: Schema } {
  const match = /^(?:common\.schema\.json)?#\/\$defs\/([A-Za-z]+)$/.exec(ref);
  const schema = match ? context.common[match[1]] : undefined;
  if (!match || !schema) {
    throw new Error(`unsupported $ref "${ref}"`);
  }
  return { name: pascal(match[1]), schema };
}

/** The schema a property really describes, after following its $ref. */
function target(schema: Schema, context: Context): Schema {
  return typeof schema.$ref === "string"
    ? resolveRef(schema.$ref, context).schema
    : schema;
}

function javadoc(text: unknown): string {
  return typeof text === "string" ? `/** ${text} */\n` : "";
}

/** The Java type of a property. Records and enums it needs are added to the context. */
function javaType(
  schema: Schema,
  owner: string,
  property: string,
  context: Context,
): string {
  const where = `${owner}.${property}`;
  checkKeywords(schema, where);
  if (typeof schema.$ref === "string") {
    const shared = resolveRef(schema.$ref, context);
    if (shared.schema.type !== "object") {
      return javaType(shared.schema, owner, property, context);
    }
    if (!context.types.has(shared.name)) {
      javaRecord(shared.name, shared.schema, null, context);
    }
    return shared.name;
  }
  if (Array.isArray(schema.enum)) {
    const name = owner.replace(/Payload$/, "") + pascal(property);
    context.types.set(name, javaEnum(name, schema));
    return name;
  }
  switch (schema.type) {
    case "integer":
      return "int";
    case "string":
      return "String";
    case "array": {
      if (!schema.items) {
        throw new Error(`${where}: array without "items"`);
      }
      const item = javaType(schema.items as Schema, owner, property, context);
      return `List<${item === "int" ? "Integer" : item}>`;
    }
  }
  throw new Error(`${where}: unsupported type "${String(schema.type)}"`);
}

function javaEnum(name: string, schema: Schema): string {
  const constants = (schema.enum as string[])
    .map((value) => `    @JsonProperty("${value}")\n    ${constantName(value)}`)
    .join(",\n");
  return `${javadoc(schema.description)}public enum ${name} {\n${constants}\n}\n`;
}

/** The checks of the compact constructor for one component: what Jackson does not check. */
function javaChecks(
  name: string,
  type: string,
  required: boolean,
  schema: Schema,
): string[] {
  const checks: string[] = [];
  const guard = (condition: string, problem: string): void => {
    const present = required ? "" : `${name} != null && `;
    checks.push(
      `if (${present}${condition}) {\n` +
        `            throw new IllegalArgumentException("${name} ${problem}");\n` +
        `        }`,
    );
  };
  if (required && type !== "int") {
    checks.push(`Objects.requireNonNull(${name}, "${name}");`);
  }
  if (typeof schema.minimum === "number") {
    guard(`${name} < ${schema.minimum}`, `must be >= ${schema.minimum}`);
  }
  if (schema.minLength === 1) {
    guard(`${name}.isEmpty()`, "must not be empty");
  } else if (typeof schema.minLength === "number") {
    const min = schema.minLength;
    guard(
      `${name}.length() < ${min}`,
      `must have at least ${plural(min, "character")}`,
    );
  }
  if (typeof schema.maxLength === "number") {
    const max = schema.maxLength;
    guard(
      `${name}.length() > ${max}`,
      `must have at most ${plural(max, "character")}`,
    );
  }
  if (typeof schema.minItems === "number") {
    const min = schema.minItems;
    guard(
      `${name}.size() < ${min}`,
      `must have at least ${plural(min, "item")}`,
    );
  }
  if (type.startsWith("List<")) {
    checks.push(
      required
        ? `${name} = List.copyOf(${name});`
        : `${name} = ${name} == null ? null : List.copyOf(${name});`,
    );
  }
  return checks;
}

/** Adds the record for an object schema to the context; message payloads also get their type. */
function javaRecord(
  name: string,
  schema: Schema,
  message: Message | null,
  context: Context,
): void {
  context.types.set(name, ""); // reserves the name while the nested types are translated
  const body =
    typeof schema.$ref === "string"
      ? { ...target(schema, context), description: schema.description }
      : schema;
  checkKeywords(body, name);
  const properties = (body.properties ?? {}) as Record<string, Schema>;
  const required = new Set((body.required ?? []) as string[]);
  const components: string[] = [];
  const checks: string[] = [];
  for (const [property, propertySchema] of Object.entries(properties)) {
    const isRequired = required.has(property);
    const type = javaType(propertySchema, name, property, context);
    const fieldType = !isRequired && type === "int" ? "Integer" : type;
    const annotation = isRequired ? "@JsonProperty(required = true) " : "";
    components.push(`        ${annotation}${fieldType} ${property}`);
    checks.push(
      ...javaChecks(
        property,
        fieldType,
        isRequired,
        target(propertySchema, context),
      ),
    );
  }
  const members: string[] = [];
  if (message) {
    members.push(`    public static final String TYPE = "${message.type}";`);
  }
  if (checks.length > 0) {
    const lines = checks.map((check) => `        ${check}`).join("\n");
    members.push(`    public ${name} {\n${lines}\n    }`);
  }
  const optional = required.size < Object.keys(properties).length;
  const implemented = message
    ? ` implements ${pascal(message.direction)}Payload`
    : "";
  context.types.set(
    name,
    javadoc(body.description) +
      (optional ? "@JsonInclude(JsonInclude.Include.NON_NULL)\n" : "") +
      `public record ${name}(\n${components.join(",\n")})${implemented} {\n` +
      (members.length > 0 ? `\n${members.join("\n\n")}\n` : "") +
      "}\n",
  );
}

function sealedInterface(direction: Direction, messages: Message[]): string {
  const permits = byDirection(messages, direction)
    .map(payloadName)
    .join(",\n        ");
  return (
    `/** A payload the ${direction} sends. */\n` +
    `public sealed interface ${pascal(direction)}Payload\n        permits ${permits} {}\n`
  );
}

function messageTypes(messages: Message[]): string {
  const entries = byDirection(messages, "client")
    .map(
      (message) =>
        `            Map.entry(${payloadName(message)}.TYPE, ${payloadName(message)}.class)`,
    )
    .join(",\n");
  const cases = byDirection(messages, "server")
    .map(
      (message) =>
        `            case ${payloadName(message)} p -> ${payloadName(message)}.TYPE;`,
    )
    .join("\n");
  return `/** Message types and payload classes, in both directions. */
public final class MessageTypes {

    /** Client payload classes by type: the server reads an incoming payload with this. */
    public static final Map<String, Class<? extends ClientPayload>> CLIENT = Map.ofEntries(
${entries});

    private MessageTypes() {}

    /** The type of a server payload, which the server writes in the envelope. */
    public static String typeOf(ServerPayload payload) {
        return switch (payload) {
${cases}
        };
    }
}
`;
}

function protocolVersion(version: Version): string {
  return `/** Protocol versions, from protocol/version.json. */
public final class ProtocolVersion {

    /** Protocol version this code speaks. */
    public static final int CURRENT = ${version.current};

    /** Oldest protocol version the server still accepts. */
    public static final int MIN_SUPPORTED = ${version.minSupported};

    private ProtocolVersion() {}
}
`;
}

const ENVELOPE = `/** The envelope shared by every message. */
public record Envelope<P>(
        @JsonProperty(required = true) String type,
        @JsonProperty(required = true) int seq,
        @JsonProperty(required = true) P payload) {

    public Envelope {
        Objects.requireNonNull(type, "type");
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be >= 1");
        }
        Objects.requireNonNull(payload, "payload");
    }
}
`;

function imports(source: string): string {
  const used = [
    source.includes("@JsonInclude") &&
      "com.fasterxml.jackson.annotation.JsonInclude",
    source.includes("@JsonProperty") &&
      "com.fasterxml.jackson.annotation.JsonProperty",
    source.includes("List<") && "java.util.List",
    source.includes("Map<") && "java.util.Map",
    source.includes("Objects.") && "java.util.Objects",
  ].filter((name): name is string => typeof name === "string");
  return used.length > 0
    ? used.map((name) => `import ${name};`).join("\n") + "\n\n"
    : "";
}

function javaFile(source: string): string {
  return `// ${NOTICE}\npackage ${JAVA_PACKAGE};\n\n${imports(source)}${source}`;
}

/** Every Java file of the package, by file name, in alphabetical order. */
export function javaFiles(
  version: Version,
  messages: Message[],
  common: Record<string, Schema>,
): Map<string, string> {
  const context: Context = { common, types: new Map() };
  for (const message of messages) {
    javaRecord(payloadName(message), message.schema, message, context);
  }
  const sources = new Map(context.types);
  sources.set("ClientPayload", sealedInterface("client", messages));
  sources.set("ServerPayload", sealedInterface("server", messages));
  sources.set("Envelope", ENVELOPE);
  sources.set("MessageTypes", messageTypes(messages));
  sources.set("ProtocolVersion", protocolVersion(version));
  const files = new Map<string, string>();
  for (const name of [...sources.keys()].sort()) {
    files.set(`${name}.java`, javaFile(sources.get(name) ?? ""));
  }
  return files;
}
