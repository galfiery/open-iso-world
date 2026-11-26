# open-iso-world

A minimal, full-stack, open source example of real-time multiplayer isometric rooms: players walk on a tile grid, see each other move and chat. It is meant as a small, readable reference that others can run, study, fork and extend. The server is authoritative: the browser only sends intentions and draws what the server says.

> open-iso-world is in early development, working on milestone v0.1 (One room). The monorepo, the database setup and the message protocol (version 1) are in place; the game itself is not playable yet. See the [roadmap](docs/ROADMAP.md) for what comes next.

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Architecture](#architecture)
- [Protocol](#protocol)
- [Getting Started](#getting-started)
- [Project Structure](#project-structure)
- [Documentation](#documentation)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [License](#license)
- [Resources](#resources)

## Overview

What exists today:

- a pnpm workspace with the protocol, the client and an example bot, and a Gradle build with the four server modules;
- PostgreSQL 18 in Docker Compose, and a Spring Boot application that connects to it;
- the message protocol: one envelope and JSON Schemas for the eight messages of v0.1;
- the protocol generator, which turns the schemas into TypeScript types and Java records (`pnpm protocol:gen`, `pnpm protocol:check`).

What v0.1 will add, one step at a time: the room engine with A* pathfinding and a ~10 Hz tick, the WebSocket endpoint with single-use tickets, the isometric renderer and click to walk. Later milestones bring chat and multiple rooms (v0.2), accounts and rooms in the database (v0.3), a public demo (v0.4) and furniture to sit on (v0.5).

Out of scope: shop, inventory, economy and avatar skins.

## Technology Stack

| Layer     | Technology                                                                             |
| --------- | -------------------------------------------------------------------------------------- |
| Server    | Java 25, Spring Boot 4.1, Gradle 9                                                     |
| Realtime  | Native Spring WebSocket, JSON envelope, no STOMP or broker                             |
| Database  | PostgreSQL 18, Flyway migrations, Spring Data JPA                                      |
| Auth      | Spring Security, JWT, single-use WebSocket ticket                                      |
| Client    | React 19, TypeScript 6.0, Vite 8, Zustand 5, TanStack Query 5                          |
| Rendering | PixiJS 8, integer zoom and nearest-neighbour pixel art                                 |
| Protocol  | JSON Schema, with generated TypeScript types and Java records                          |
| Tests     | JUnit 6, ArchUnit, Testcontainers, Vitest, Playwright, Gatling                         |
| Tooling   | Node.js 26, pnpm 12, ESLint 10 with eslint-plugin-boundaries, Prettier, Docker Compose |

## Architecture

```text
Browser   ui (React) ──► game ──► net        engine (PixiJS) ──► game
                                   │
                    REST /api/*, WebSocket /ws?ticket=…
                                   ▼
Server    app ──► net ──► core ◄── persistence ──► PostgreSQL
                          rooms, tick loop, pathfinding
```

Three rules shape everything else:

1. **The server has the last word.** The client sends only intentions (go to a tile, say something); the server validates them and tells everyone the result.
2. **The server thinks in grid coordinates.** Isometric projection exists only in the client's `engine`.
3. **One protocol.** Every message is defined once in `protocol/` as JSON Schema.

Each active room lives in memory with its own command queue and tick loop, run by one thread at a time, so game state needs no locks. The tick loop never waits on the database.

Details: [Architecture Overview](docs/Architecture/Overview.md).

## Protocol

### Connection Lifecycle

1. The client gets a single-use ticket over REST.
2. It opens `/ws?ticket=…`.
3. It sends `hello` with its protocol version; the server answers `welcome` with the player's id, then `roomState` with the room and the players in it.
4. From then on the client sends `moveTo`, and the server sends each tick's events (`path`, `playerJoined`, `playerLeft`) as one frame.

### Message Format

```json
{ "type": "moveTo", "seq": 12, "payload": { "x": 4, "y": 7 } }
```

| Field     | Type              | Meaning                                                    |
| --------- | ----------------- | ---------------------------------------------------------- |
| `type`    | string, camelCase | The message type, one of the schemas in `protocol/schemas` |
| `seq`     | integer ≥ 1       | Counts the sender's own messages on this connection        |
| `payload` | object            | The message body, as its schema defines it                 |

Full reference: [Realtime Protocol](docs/Realtime-Protocol/Overview.md).

## Getting Started

### Prerequisites

- JDK 25 (Gradle downloads it if it is missing)
- Node.js 26 and pnpm 12
- Docker, for the local database and for the server tests (Testcontainers)

### Building

```bash
cp .env.example .env                 # once; local development values only
docker compose up -d db              # PostgreSQL on port 5434
pnpm install

pnpm server:dev                      # server on :8080, from the root on any OS
cd server && ./gradlew test          # JUnit, ArchUnit, Testcontainers

pnpm protocol:gen                    # regenerate code from protocol/schemas
pnpm protocol:check                  # fail if the generated code is stale
```

The client and the bot have their scripts in place (`pnpm --filter open-iso-world-client dev`, `pnpm --filter bot start`), but they only print a placeholder until their roadmap step is done.

### Configuration

Copy `.env.example` to `.env` in the repository root; `.env` is never committed. Docker Compose and the server both read it.

| Key                                                 | Purpose                                          |
| --------------------------------------------------- | ------------------------------------------------ |
| `POSTGRES_HOST`, `POSTGRES_PORT`                    | Where the database listens (`localhost`, `5434`) |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | The database and its credentials                 |
| `SERVER_PORT`                                       | The server's HTTP and WebSocket port (`8080`)    |

Everything prefixed with `VITE_` ends up in the browser, so never put secrets there.

## Project Structure

```text
protocol/          message schemas, version.json and the code generator
server/
  core/            pure game logic: rooms, tick loop, grid, pathfinding, chat
  net/             WebSocket transport
  persistence/     database access and Flyway migrations
  app/             Spring Boot application: wiring, configuration, REST, security
client/src/
  net/             WebSocket and REST
  engine/          isometric math and PixiJS rendering: iso/, scene/, sprites/, camera/, input/
  game/            game state, plain TypeScript
  ui/              React
assets/            CC0 placeholder art
examples/bot/      headless client that joins a room, walks and chats
docs/              documentation and ADRs
scripts/           repository tooling, such as the cross-platform Gradle runner
docker-compose.yml local PostgreSQL
```

### Key Entry Points

| What                 | File                                                                        |
| -------------------- | --------------------------------------------------------------------------- |
| Server application   | `server/app/src/main/java/io/openisoworld/app/OpenIsoWorldApplication.java` |
| Server configuration | `server/app/src/main/resources/application.yml`                             |
| Message schemas      | `protocol/schemas/`                                                         |
| Protocol version     | `protocol/version.json`                                                     |

## Documentation

All pages are listed on the [documentation home](docs/Home.md).

- [Architecture Overview](docs/Architecture/Overview.md)
- [Realtime Protocol](docs/Realtime-Protocol/Overview.md)
- [Roadmap](docs/ROADMAP.md)

## Compatibility

Client and server are released together, and the handshake keeps them in step: a client outside the server's supported protocol range is refused with `error` `unsupportedProtocolVersion`.

### Requirements

- Protocol version 1 (`protocol/version.json`: `current` 1, `minSupported` 1)
- JSON text frames over WebSocket
- A current desktop browser with WebGL, for PixiJS
- PostgreSQL 18

## Contributing

1. Read [CLAUDE.md](CLAUDE.md): it holds the rules of the project.
2. Work on a branch from `develop`: `feat/…`, `fix/…`, `refactor/…` or `docs/…`. Nobody pushes to `master`.
3. Follow Conventional Commits, and keep one focused change per pull request.
4. Ship every change with tests. A bug fix comes with a test that failed before it.
5. Change the database only with a new Flyway migration, and the protocol only by editing the schemas and regenerating the code.
6. Architecture changes and out-of-scope features need an ADR in `docs/adr/` first.

## License

[Apache License 2.0](LICENSE).

## Resources

- [Documentation home](docs/Home.md)
- [Architecture overview](docs/Architecture/Overview.md)
- [Realtime protocol](docs/Realtime-Protocol/Overview.md)
- [Message schemas](protocol/schemas/)
- [Architectural decisions](docs/adr/)
- [Roadmap](docs/ROADMAP.md)
