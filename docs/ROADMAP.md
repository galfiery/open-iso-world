# Roadmap

Milestones are listed in order. There are no dates: each milestone starts when the previous one is done.

Out of scope: shop, inventory, economy, avatar skins.

## v0.1 One room

A single room where players walk on a tile grid and see each other move.

- [x] Monorepo: pnpm workspace, Gradle modules, PostgreSQL in Docker Compose
- [x] Protocol: `{ "type", "seq", "payload" }` envelope and JSON Schemas for `hello` (with protocol version), `welcome`, `roomState`, `moveTo`, `path`, `playerJoined`, `playerLeft`, `error`
- [x] Protocol generator: TypeScript types and Java records, `pnpm protocol:gen` and `pnpm protocol:check`
- [x] Server core: room grid with blocked tiles, A* pathfinding, ~10 Hz tick loop with a command queue, batched events per tick
- [ ] Server net: native WebSocket endpoint, single-use connection ticket issued by REST (anonymous guests use a ticket too)
- [ ] Server security: validate every coordinate, rate limit of ~5 moves per second per player
- [ ] Client engine: isometric math, tile and avatar rendering, one depth-sorted layer, integer zoom and `roundPixels`
- [ ] Client game: room state store, tile-by-tile path interpolation
- [ ] Client input: click to tile, send `moveTo`
- [ ] ArchUnit and eslint-plugin-boundaries rules for the layers
- [ ] Docker Compose `full` profile (db, server, client)
- [x] CI on GitHub Actions: protocol check, server tests, client lint, tests and build

## v0.2 Social

Several players talk to each other across several rooms.

- [ ] Guest entry with a nickname
- [ ] Chat: `say` message, server-side validation and rate limit, speech bubbles on the client
- [ ] Multiple rooms in memory, room list over REST, join and leave
- [ ] Example bot in `examples/bot`: joins a room, walks to random tiles and chats

## v0.3 Persistence

Rooms survive a restart and players can keep an account.

- [ ] Rooms and their layouts in PostgreSQL (Flyway migrations), loaded without blocking the tick loop
- [ ] Optional accounts: sign-up and login with BCrypt or Argon2 password hashes
- [ ] JWT access tokens and refresh tokens (only their hashes stored)
- [ ] Logged-in players get a WebSocket ticket the same way guests do

## v0.4 Publication

Others can find, try and contribute to the project.

- [ ] Online demo
- [ ] README with a GIF and quick start
- [ ] ADRs for the main decisions in `docs/adr/`
- [ ] CONTRIBUTING guide

## v0.5 Extensibility

The project shows how to grow beyond the basics.

- [ ] Furniture placed in rooms, sharing the depth-sorted layer
- [ ] Sitting on furniture
- [ ] Guide to extending the project (new message, new furniture, new bot behaviour)

## Later

- [ ] A minigame
- [ ] Load tests with Gatling
- [ ] Reconnection
