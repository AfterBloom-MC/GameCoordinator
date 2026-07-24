### Base Minigame Plugin Specification (AfterBloom MC)

This document outlines the requirements for the base plugin that all minigames on the AfterBloom network must be built upon. Since the `GameCoordinator` does not run on game servers, each game server must implement the same communication protocols to interact with other servers and the network as a whole.

#### 1. Core Responsibilities
- **Self-Registration**: Every game server must announce itself to the network.
- **Heartbeat**: Periodic state updates (every 10 seconds) containing player counts, player names, and server status.
- **Communication Protocol**: Must use Redis PUB/SUB for all inter-server messages.
- **Game State Management**: Track the server's "Game" type (e.g., `lobby`, `bedwars`) as configured in `config.yml`.

#### 2. Redis Communication Schema
All messages should be JSON objects sent over Redis.

##### Common Fields
- `senderId`: Unique identifier for the server (e.g., `game-survival-01`).
- `receiverId`: Target server ID or `*` for broadcast.
- `function`: The action being performed.

##### Channels
- `discover`: Channel for heartbeats, registration, and network-wide alerts.
- `game`: Channel for matchmaking and game-specific coordination.

#### 3. Required Functions to Implement

##### A. Heartbeat (Outbound)
Sent every 10 seconds to the `discover` channel.
```json
{
  "senderId": "game-01",
  "receiverId": "*",
  "function": "heartbeat",
  "serverGame": "bedwars",
  "playerCount": 12,
  "lobbyCount": 1,
  "players": ["Player1", "Player2", "..."]
}
```

##### B. Coordinator Start (Inbound)
Listen for `announceCoordinatorStart` from `coord-*`. Upon receiving, the game server should immediately send a heartbeat to re-register.

##### C. Total Players Request (Inbound/Outbound)
- **Outbound**: To request the network total, send `requestTotalPlayers`.
- **Inbound**: Listen for `totalPlayersResponse` and update local placeholders.

##### D. Staff System Support (Inbound/Outbound)
- **Report Alerts**:
  - **Outbound**: When a player runs `/report`, the game server should send a `reportAlert` to `coord-*` on the `discover` channel.
  ```json
  {
    "receiverId": "coord-*",
    "senderId": "game-01",
    "function": "reportAlert",
    "reporter": "PlayerName",
    "target": "TargetName",
    "reason": "Reason for report",
    "server": "game-01"
  }
  ```
  *Note: Game servers should NOT send Discord webhooks. The Coordinator handles all Discord integrations.*

  - **Inbound**: Listen for `reportAlert` on the `discover` channel (broadcast from the Coordinator).
  Action: Broadcast the message `§8[§c§lREPORT§8] §f[reporter] §7reported §f[target] §7on §f[server] §7for: §f[reason]` to all local players with `minigames.staff` permission.

- **Teleport Requests**: Listen for `teleportRequest` on the `discover` channel.
  ```json
  {
    "receiverId": "game-01",
    "senderId": "coord-01",
    "function": "teleportRequest",
    "staffPlayer": "StaffName",
    "targetPlayer": "TargetName"
  }
  ```
  Action:
  1. Verify `targetPlayer` is online locally.
  2. If `staffPlayer` joins this server shortly after, or is already present, they MUST be placed in **Vanish** and **Spectator** mode.
  3. Teleport `staffPlayer` to `targetPlayer`.

##### E. Find Player Result (Inbound/Outbound)
- **Outbound**: To find a player, the Coordinator will check its local registry. If not found, it might broadcast a query.
- **Inbound**: Not strictly required as the Coordinator uses heartbeats to track players, but games should be ready to respond to specific queries if added.

##### F. Discord Webhooks
All Discord integrations, such as reporting or server status alerts, are handled EXCLUSIVELY by the `GameCoordinator`. Base plugins should never attempt to contact Discord directly. Instead, they should send the appropriate Redis messages (e.g., `reportAlert`) to `coord-*`.

#### 4. Staff Mode & Vanish Persistence
To ensure seamless cross-server staff utility, the base plugin must:
- Handle a `minigames.staff` permission node for basic staff access.
- Implement a **Vanish** system that:
    - Hides the player from all non-staff members.
    - Persists across server switches (e.g., if a staff member is vanished on one server, they should arrive vanished on the next if possible, or be vanished immediately upon arrival via `teleportRequest`).
- Support **Spectator Mode** for staff following players.

#### 6. Stats Reporting (Outbound)
Game servers are responsible for reporting game results/stats to the `GameCoordinator` via the `game` channel.
The message consists of two JSON objects concatenated together (Envelope + Stats Data).
```json
{
  "receiverId": "coord-*",
  "senderId": "game-01",
  "function": "stats",
  "serverGame": "bedwars"
}{
  "uuid1": {"kills": 5, "wins": 1},
  "uuid2": {"kills": 2, "deaths": 1}
}
```
*Note: This specific format is required for the Coordinator's parser.*

#### 7. Database Configuration
Game servers do not need direct access to the `afterbloom` stats database; they report via Redis as shown above. However, they may need access to other shared databases if required by specific game logic.

#### 8. Standardized Permissions
- `minigames.staff`: General staff access, receives reports.
- `minigames.staff.follow`: Permission to use follow/teleport features.
- `minigames.staff.find`: Permission to use find features.
- `minigames.manage`: Network management/admin permissions.

#### 9. Suggested Architecture
- **Redis Manager**: Handles connection, subscription, and publishing.
- **State Manager**: Tracks local players and server metadata.
- **Staff Manager**: Handles vanish logic and cross-server staff alerts.
- **Placeholder Integration**: Provide `%network_total%` and other global stats to players.
