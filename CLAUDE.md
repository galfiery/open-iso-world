# CLAUDE.md

## 1. Project Overview

A minimal, full-stack, open source example of real-time multiplayer isometric rooms: players walk on a tile grid, see each other move and chat.

- Project name: open-iso-world
- Main purpose: a small, readable reference that others can run, study, fork and extend.
- Target users: developers who want to build a multiplayer isometric game or learn how one works.
- Current development status: early development. Roadmap:
  - **v0.1 One room:** monorepo, protocol, WebSocket, rendering, click to walk with pathfinding, Docker Compose and CI.
  - **v0.2 Social:** chat, multiple rooms, guest entry.
  - **v0.3 Persistence:** rooms in the database, optional accounts.
  - **v0.4 Publication:** online demo, README with a GIF, ADRs, license, CONTRIBUTING.
  - **v0.5 Extensibility:** furniture to sit on, example bot, guide to extending the project.
  - **Later:** a minigame, load tests, reconnection.

Out of scope: shop, inventory, economy, avatar skins.

## 2. Tech Stack

- Server: Java 25, Spring Boot 4.1 (native WebSocket, no STOMP), Spring Security (JWT), Gradle 9
- Database: PostgreSQL 18, Spring Data JPA, Flyway
- Client: TypeScript 6.0, React 19, Vite 8, PixiJS 8, Zustand 5, TanStack Query 5
- Runtime and tooling: Node.js 26, pnpm 12, ESLint 10 + eslint-plugin-boundaries, Prettier, Docker Compose
- Protocol: JSON Schema, with generated TypeScript types and Java records
- Testing: JUnit 6, ArchUnit, Testcontainers, Vitest, Playwright, Gatling

Always respect the existing dependencies and versions. Java library versions come from the Spring Boot BOM.
Do not introduce new dependencies or upgrade a major version without justification.

## 3. Project Structure

```text
protocol/          # Message schemas and the code generator
server/
  core/            # Pure game logic: rooms, tick loop, grid, pathfinding, chat
  net/             # WebSocket transport
  persistence/     # Database access and Flyway migrations
  app/             # Spring Boot application: wiring, configuration, REST, security
client/src/
  net/             # WebSocket and REST
  engine/          # Isometric math and PixiJS rendering
    iso/           # Pure isometric math: grid <-> screen, no PixiJS
    scene/         # The single depth-sorted layer for avatars and furniture
    sprites/       # Avatar, furniture and tile views
    camera/        # Integer zoom, pan and roundPixels
    input/         # Click to tile
  game/            # Game state, pure TypeScript
  ui/              # React: room list, room, chat
assets/            # CC0 placeholder art and asset specs
examples/bot/      # Headless client that joins a room, walks and chats
docs/              # Documentation and ADRs
scripts/           # Repository tooling, such as the cross-platform Gradle runner
```

Follow the existing structure when adding new files.

## 4. Architecture

- **The server has the last word.** The client sends only intentions (go to a tile, say something); the server validates them, computes the path and broadcasts the result.
- **The server works in grid coordinates.** Isometric projection and depth sorting exist only in `client/src/engine`.
- **One room, one simulation.** Each active room lives in memory with its own command queue and a ~10 Hz tick loop, run by one thread at a time. No locks on game state.
- **Paths, not positions.** One move message carries the whole path; clients interpolate tile by tile. Events of the same tick go out as one batch.
- **One protocol source.** Every message is defined in `protocol/` and shares one envelope: `{ "type", "seq", "payload" }`. The client declares its protocol version on connect.
- **Server layers:** `app → net, persistence → core`. `core` has no Spring, JPA or I/O and talks to the rest through interfaces that `net` and `persistence` implement. The tick loop never waits on the database.
- **Client layers:** `ui → game, engine`, `engine → game`, `game → net`. Only `net` touches the network, only `engine` uses PixiJS, only `ui` uses React. A single React component mounts the engine, and there is no React re-render per frame.
- PostgreSQL holds accounts and rooms. Guests, positions, presence and chat stay in memory.

## 5. Development Commands

Try the demo (Docker only):

```bash
cp .env.example .env
docker compose --profile full up -d --build    # open http://localhost:5173
```

Develop (Java 25, Node.js 26, pnpm 12, Docker):

```bash
cp .env.example .env
docker compose up -d db                        # PostgreSQL only
pnpm install
pnpm protocol:gen                              # regenerate code from protocol/
pnpm protocol:check                            # fail if generated code is stale
cd server && ./gradlew :app:bootRun            # server on :8080
pnpm server:dev                                # same, from the root on any OS
cd server && ./gradlew test                    # JUnit, ArchUnit, Testcontainers
pnpm --filter open-iso-world-client dev        # client on :5173
pnpm --filter open-iso-world-client lint
pnpm --filter open-iso-world-client test
pnpm --filter open-iso-world-client build
pnpm --filter open-iso-world-client e2e        # Playwright
pnpm --filter bot start                        # example bot
```

Use the existing scripts and Gradle tasks whenever possible.

## 6. Coding Conventions

- All names in code are in English.
- TypeScript strict mode; prefer explicit types over `any`.
- Java: packages lowercase and singular, classes named by role (`RoomManager`, `RoomController`).
- React components in PascalCase, one per file; other TypeScript modules in camelCase.
- Protocol message types, field names and enum values in camelCase, with schema files named after the type in kebab-case (`player-joined.schema.json`); tables and columns in snake_case; migrations as `V<number>__<description>.sql`.
- Keep functions small and files short: this is an example project and should read easily.
- Unit tests sit next to the file they test.

## 7. Development Rules

- Understand the existing implementation before modifying it.
- Prefer minimal, focused changes; avoid changing unrelated files.
- Do not introduce unnecessary abstractions.
- Never edit generated protocol code by hand. To change a message, edit `protocol/`, regenerate, and update server and client together.
- Database changes always come as a new Flyway migration. Never edit a merged migration.
- Do not bump the protocol version silently, and never make a breaking change without bumping it.
- Features out of scope, or changes to the architecture above, need an ADR in `docs/adr/` first.

## 8. Testing

- Add or update tests for new functionality; a bug fix gets a test that fails before the fix.
- Run the relevant tests after modifications.
- CI runs the protocol check, server tests and client lint, tests and build; it must be green.
- Do not disable or weaken tests, ArchUnit rules or lint rules to make the build pass.
- Include edge cases, such as blocked tiles, room borders and rate limits.

## 9. Security

- Never trust the client for game state; validate every coordinate, room access and chat message on the server.
- Rate-limit commands per player (about 5 moves per second).
- Never hardcode credentials or keys; secrets go in `.env`, which is never committed. `VITE_` variables are public.
- The WebSocket connects with a single-use ticket, so tokens never appear in URLs or logs.
- Hash passwords with Argon2 or BCrypt and store only hashes of refresh tokens.

## 10. Git Workflow

- Follow Conventional Commits.
- Branches: `feat/`, `fix/`, `refactor/`, `docs/`.
- One focused change per pull request; never push to `main`.
- Do not commit secrets, temporary artifacts or assets that are not CC0. Generated protocol code is committed.
- Do not execute destructive Git commands without authorization.

## 11. Common Pitfalls

- Isometric or screen coordinates must never reach the server or the protocol.
- Calling the database from the tick loop blocks the whole room.
- Avatars and furniture must share one depth-sorted layer, or depth sorting breaks.
- Pixel art needs nearest-neighbour scaling, integer zoom and `roundPixels`, or it blurs.
- Spring Boot 4 uses Jackson 3 (`tools.jackson.*`); Testcontainers 2 uses the `testcontainers-postgresql` module.
- TypeScript stays on 6.0 until typescript-eslint supports TypeScript 7.

## 12. Instructions for Claude

- Prioritize correctness and readability: other people learn from this code.
- Ask for clarification when requirements are ambiguous or touch the scope or architecture.
- Explain important architectural decisions and propose an ADR when one is needed.
- Report which files were modified.
- Report tests executed and their results.
- Do not claim tests passed if they were not executed.
