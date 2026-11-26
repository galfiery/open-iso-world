# open-iso-world Documentation

Welcome to the open-iso-world documentation. These pages explain how a small real-time multiplayer isometric game works, from the room simulation on the server to the tiles on screen. They describe the project during milestone v0.1 (One room), with protocol version 1.

Start with the [Architecture Overview](Architecture/Overview.md), then follow the area you are working on.

## Architecture

| Page                                 | What it covers                                                                                      |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| [Overview](Architecture/Overview.md) | The big picture: client, server, database, the three rules behind them, and the layers of each side |

## Realtime Protocol

| Page                                      | What it covers                                                                                                       |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| [Overview](Realtime-Protocol/Overview.md) | Connecting, the envelope and frames, every message with its fields, error codes, versioning and how to add a message |

## Planning

| Page                  | What it covers                                             |
| --------------------- | ---------------------------------------------------------- |
| [Roadmap](Roadmap.md) | The milestones from v0.1 to v0.5, and what is out of scope |

## Not documented yet

These systems are on the [roadmap](Roadmap.md) but do not exist yet: the room engine (grid, A* pathfinding, tick loop), the WebSocket transport and tickets, the client engine (isometric rendering, camera, input), chat and multiple rooms (v0.2), accounts and rooms in the database (v0.3), and the example bot. Each gets its page when it is built.

## Decisions

The [architectural decision records](adr/) explain why things are as they are. There are none yet.
