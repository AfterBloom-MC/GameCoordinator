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
{ "receiverId": "*", "senderId": "coord-<id>", "function": "heartbeat", "playerCount": 10, "lobbyCount": 1, "serverGame": "lobby" }
```

Note: Coordinators track each other to ensure "Total Players" placeholders are accurate across the network. If `serverGame` is present, it's also tracked as a game server.

### 3) Game server heartbeat (game → coordinator)
Channel: `discoverChannel`
```json
{ "receiverId": "coord-*", "senderId": "game-<id>", "serverGame": "<gameName>", "playerCount": 10, "lobbyCount": 1, "function": "heartbeat" }
```
- On receipt, the coordinator updates/creates an entry for the game server and refreshes its last‑seen timestamp.
- `playerCount` and `lobbyCount` are optional (default to 0 if omitted).
- Note: Supporter mode servers use these heartbeats only to track individual servers. For the global total player count, they use `requestTotalPlayers`.

### 4) Request total players (supporter → coordinators)
Channel: `discoverChannel`
```json
{ "receiverId": "coord-*", "senderId": "supporter-<id>", "function": "requestTotalPlayers", "requestId": 123456789 }
```
- Used by servers in supporter mode to ask coordinators for the calculated network-wide player total.
- `requestId` should be a unique timestamp or incrementing long to correlate responses.

### 5) Total players response (coordinator → supporter)
Channel: `discoverChannel`
```json
{ "receiverId": "supporter-<id>", "senderId": "coord-<id>", "function": "totalPlayersResponse", "totalPlayers": 100, "requestId": 123456789 }
```
- A coordinator replies with its currently calculated total.
- The supporter uses the `requestId` to ensure it only updates its cache with the most recent information.

### 6) Find server request (coordinator → game)
Channel: `gameChannel`
```json
{ "receiverId": "game-<id>", "senderId": "coord-<id>", "function": "findServer", "player": "<PlayerName>" }
```
- Initiates matchmaking for a given player on a specific target server from the queue.

### 7) Find server result (game → coordinator)
Channel: `gameChannel`
```json
{ "receiverId": "coord-<id>", "senderId": "game-<id>", "function": "findServerResult", "player": "<PlayerName>", "accepted": true }
```
- If `accepted` is `true`, the coordinator clears the player’s queue and proceeds to transfer.
- If `false`, the coordinator moves on to the next queued server for that player.

### 8) End-of-game stats (game → coordinator)
Channel: `gameChannel`

Two-JSON message format sent in a single Redis publish. First JSON is the envelope, second JSON is a map of player UUID → stat object.

Envelope:
```json
{ "receiverId": "coord-<id>", "senderId": "game-<id>", "function": "stats", "serverGame": "<gameName>" }
```
Stats payload (immediately following in the same message):
```json
{
  "<uuid-1>": { "kills": 5, "wins": 1, "playtime": 15 },
  "<uuid-2>": { "kills": 2, "wins": 0, "playtime": 10 }
}
```
Coordinator behavior:
- Validates `receiverId` addressing and presence of `serverGame` in the envelope.
- Persists stats in per‑game, columnar tables with four time slices: total, weekly, monthly, yearly.
- Also persists an aggregate "global" version of every stat across all minigames (table prefix `global_stats_`).
- Table names: `<game>_stats_total`, `<game>_stats_weekly`, etc., AND `global_stats_total`, `global_stats_weekly`, etc.
- Row key: `player_uuid` (CHAR(36) PRIMARY KEY).
- Stats keys become DOUBLE columns; new keys will transparently `ALTER TABLE ADD COLUMN`.
- Incoming values are treated as deltas and added to the current values (increment), not set.
- Before each time-slice reset (weekly: Mondays 00:00; monthly: 1st 00:00; yearly: Jan 1st 00:00 — server timezone), the table is backed up to a JSON file under `plugins/GameCoordinator/Stats-Data-Historical/<game>/<slice>/` and then truncated.
- Logs the number of players ingested. Malformed UUID keys are skipped.

### 9) Send player (coordinator → proxy)
Channel: `gameChannel` (or future `proxyChannel`)
```json
{ "receiverId": "proxy", "senderId": "coord-<id>", "function": "sendPlayer", "destinationServer": "game-<id>", "player": "<PlayerName>" }
```
- The Velocity proxy listens for this and connects the player to `destinationServer`.

### 10) Send player result (proxy → coordinator) [optional]
Channel: `gameChannel` (or future `proxyChannel`)
```json
{ "receiverId": "coord-<id>", "senderId": "proxy-<id>", "function": "sendPlayerResult", "player": "<PlayerName>", "destinationServer": "game-<id>", "success": true }
```

## PlaceholderAPI

If PlaceholderAPI is installed, the following placeholders are available:

- `%gamecoordinator_total_players%`: Total number of players across all tracked remote servers (including other coordinators) AND the current coordinator server.
- `%gamecoordinator_players_<gametype>%`: Total number of players in a specific game type. (Includes the current server if `serverGame` is configured in `config.yml`)
- `%gamecoordinator_servers_<gametype>%`: Number of servers hosting a specific game type. (Includes the current server if `serverGame` matches)
- `%gamecoordinator_lobbies_<gametype>%`: Total number of lobbies for a specific game type. (Includes the current server as 1 lobby if `serverGame` matches)
- `%gamecoordinator_playtime_total%`: Player's total playtime across all minigames (formatted).
- `%gamecoordinator_playtime_<gametype>%`: Player's total playtime in a specific game type (formatted).

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
