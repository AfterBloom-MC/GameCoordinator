# GameCoordinator

AfterBloom MC's solution to managing Minigames. The coordinator handles:

* Managing which games are active
* Player MatchMaking
* Provisioning Backup servers
* long running games (roblox tycoon styled)
* Custom Events triggered by admins
* and more to come soon!

The system uses Redis pub/sub to communicate with game servers and the proxy through a small JSON protocol.

Below is the initial draft of that protocol and the channels used.

## Channels

All channels are configurable in `config.yml`.

- `discoverChannel` (default: `discover`)
  - Used for coordinator announcements and heartbeats, and for game servers to send heartbeats to the coordinator.
- `gameChannel` (default: `game`)
  - Used for matchmaking coordination between the coordinator and game servers.
- (Optional) `proxyChannel` (not yet implemented)
  - If introduced later, used for coordinator → proxy transfer commands; for now, `sendPlayer` can be sent on `gameChannel`.

## Addressing

Each message contains a `receiverId` indicating who should process it:

- `"*"` broadcast to any listener
- `"coord-<id>"` a specific coordinator
- `"coord-*"` any coordinator
- `"game-<id>"` a specific game server
- `"game-*"` any game server
- `"proxy"` or `"proxy-<id>"` a proxy instance (Velocity)

We standardize on `receiverId`. For compatibility, the coordinator currently also accepts legacy messages using `recieverId`.

## Message types

### 1) Coordinator announce (coordinator → games)
Channel: `discoverChannel`
```json
{ "receiverId": "game-*", "senderId": "coord-<id>", "function": "announceCoordinatorStart" }
```

### 2) Coordinator heartbeat (coordinator → broadcast)
Channel: `discoverChannel`, sent every 10 seconds
```json
{ "receiverId": "*", "senderId": "coord-<id>", "function": "heartbeat" }
```

Note: game servers should ignore this unless they specifically need to track coordinator liveness. The coordinator will not create server entries from coordinator heartbeats.

### 3) Game server heartbeat (game → coordinator)
Channel: `discoverChannel`
```json
{ "receiverId": "coord-*", "senderId": "game-<id>", "serverGame": "<gameName>", "function": "heartbeat" }
```
- On receipt, the coordinator updates/creates an entry for the game server and refreshes its last‑seen timestamp.

### 4) Find server request (coordinator → game)
Channel: `gameChannel`
```json
{ "receiverId": "game-<id>", "senderId": "coord-<id>", "function": "findServer", "player": "<PlayerName>" }
```
- Initiates matchmaking for a given player on a specific target server from the queue.

### 5) Find server result (game → coordinator)
Channel: `gameChannel`
```json
{ "receiverId": "coord-<id>", "senderId": "game-<id>", "function": "findServerResult", "player": "<PlayerName>", "accepted": true }
```
- If `accepted` is `true`, the coordinator clears the player’s queue and proceeds to transfer.
- If `false`, the coordinator moves on to the next queued server for that player.

### 6) End-of-game stats (game → coordinator)
Channel: `gameChannel`

Two-JSON message format sent in a single Redis publish. First JSON is the envelope, second JSON is a map of player UUID → stat object.

Envelope:
```json
{ "receiverId": "coord-<id>", "senderId": "game-<id>", "function": "stats", "serverGame": "<gameName>" }
```
Stats payload (immediately following in the same message):
```json
{
  "<uuid-1>": { "kills": 5, "wins": 1 },
  "<uuid-2>": { "kills": 2, "wins": 0 }
}
```
Coordinator behavior:
- Validates `receiverId` addressing and presence of `serverGame` in the envelope.
- Persists stats in per‑game, columnar tables with four time slices: total, weekly, monthly, yearly.
  - Table names: `<game>_stats_total`, `<game>_stats_weekly`, `<game>_stats_monthly`, `<game>_stats_yearly` (game id normalized to `[a-z0-9_]`).
  - Row key: `player_uuid` (CHAR(36) PRIMARY KEY).
  - Stats keys become DOUBLE columns; new keys will transparently `ALTER TABLE ADD COLUMN`.
  - Incoming values are treated as deltas and added to the current values (increment), not set.
- Before each time-slice reset (weekly: Mondays 00:00; monthly: 1st 00:00; yearly: Jan 1st 00:00 — server timezone), the table is backed up to a JSON file under `plugins/GameCoordinator/Stats-Data-Historical/<game>/<slice>/` and then truncated.
- Logs the number of players ingested. Malformed UUID keys are skipped.

### 7) Send player (coordinator → proxy)
Channel: `gameChannel` (or future `proxyChannel`)
```json
{ "receiverId": "proxy", "senderId": "coord-<id>", "function": "sendPlayer", "destinationServer": "game-<id>", "player": "<PlayerName>" }
```
- The Velocity proxy listens for this and connects the player to `destinationServer`.

### 8) Send player result (proxy → coordinator) [optional]
Channel: `gameChannel` (or future `proxyChannel`)
```json
{ "receiverId": "coord-<id>", "senderId": "proxy-<id>", "function": "sendPlayerResult", "player": "<PlayerName>", "destinationServer": "game-<id>", "success": true }
```

## Implementation notes

- Heartbeats: the coordinator now sends its heartbeat JSON every 10 seconds on `discoverChannel`. Game server heartbeats must include `serverGame` to be registered; coordinator heartbeats are ignored by the server registry.
- Matchmaking: the coordinator uses a per‑player queue (`Utils`) and sends `findServer` on `gameChannel`. Games reply with `findServerResult`.
- Transfer: the proxy component should subscribe to the channel, validate addressing, and move the player to the requested server; Acking is optional but recommended.

## Future improvements

- Migration complete: All docs and coordinator messages now use `receiverId`. For compatibility, the coordinator still accepts legacy `recieverId` from external producers.
- Add `requestId` and `playerId` (UUID) to correlate requests and prefer UUID identity.
- Consider a dedicated `proxyChannel` for proxy‑only commands.

---

Note: If you are not an AfterBloom dev Please don't open issues here! Use the bug reports system in discord (.gg/afterbloom)

(and no, that webhook in commit history will not work)
