### Scalability and Multi-Coordinator Readiness Summary

This document summarizes the current state of the GameCoordinator system regarding parallel execution and provides recommendations for future improvements to ensure a robust, multi-node environment.

### 1. Strengths (Multi-Node Readiness)

*   **Stateless, Distributed Architecture**: The system relies on Redis for real-time messaging and MySQL for persistent storage. This decoupling allows multiple instances of the coordinator to run simultaneously without needing direct inter-node communication.
*   **Intelligent Addressing Logic**: The `Redis` class implements a granular addressing system:
    *   `*`: Global broadcast.
    *   `coord-*`: Targets all active coordinators.
    *   `supporter-*`: Targets all nodes in supporter mode.
    *   `coord-01`: Targets a specific instance.
    This allows for flexible message routing and specialized task handling.
*   **Atomic Database Operations**: The `StatsStorage` class utilizes `INSERT ... ON DUPLICATE KEY UPDATE` with `COALESCE(col,0) + VALUES(col)`. This ensures that even if multiple coordinators ingest stats for the same player at the exact same millisecond, the data remains consistent and no updates are lost.
*   **Conflict Resolution in State**: The `Servers` class uses a `requestId` (timestamp-based) mechanism to ensure that global stats updates are processed in the correct order. This prevents "race conditions" where an older update could potentially overwrite a newer one.
*   **Dynamic Schema Management**: Tables and columns are created on-the-fly in MySQL. Multiple coordinators can safely attempt to create the same tables/columns due to `IF NOT EXISTS` logic and internal `schemaLocks`.

### 2. Suggestions for Improvement

#### A. Discord Webhook De-duplication
**Current Issue**: When a game server sends a `reportAlert` to `coord-*`, every running coordinator receives it and attempts to post it to Discord. This results in duplicate notifications.
**Suggestion**:
*   Implement a **Leader Election** system using Redis (e.g., using `SET NX` with an expiry) so only one "Primary" coordinator handles Discord webhooks.
*   Alternatively, modify game servers to target only one specific coordinator ID for Discord-related tasks, using others as failovers.

#### B. Heartbeat Synchronization
**Current Issue**: Every node independently calculates network totals and heartbeats. While this is good for redundancy, it creates redundant Redis traffic.
**Suggestion**:
*   Centralize the "Network Total" calculation. Instead of every node broadcasting its own view, the Leader node could broadcast a authoritative `networkTotalPlayers` message once every cycle.

#### C. Redis Load Distribution
**Current Issue**: All nodes listen to the same `discover` and `game` channels. As the number of game servers and coordinators grows, the volume of messages processed by every node increases linearly.
**Suggestion**:
*   Introduce **Sharding** or more specific channels for different game types (e.g., `game.bedwars`, `game.lobby`).
*   Move from a pure Pub/Sub model to a **Redis Stream** with **Consumer Groups** for tasks that only need to be handled by *one* coordinator (like stats ingestion or Discord reporting).

#### D. Database Connection Pooling
**Current Issue**: While HikariCP is used, multiple coordinators each maintaining their own pool can quickly hit the MySQL `max_connections` limit.
**Suggestion**:
*   Monitor database connection usage closely.
*   Consider a database proxy like **ProxySQL** if scaling beyond 5-10 coordinators to manage connection multiplexing.

#### E. Health Monitoring
**Current Issue**: There is currently no automated way for one coordinator to know if another has crashed, other than seeing its heartbeat stop in the `Servers` registry.
**Suggestion**:
*   Implement a `/coordinator status` command that shows the health and "role" (Leader/Follower) of all active coordinators in the network.

### 3. Automated Scaling Strategy
For a detailed roadmap on how to move to a fully automated scaling system (cloning servers, dynamic IDs, and cloud integration), please refer to:
**[AUTOMATED_SCALING_GUIDE.md](./AUTOMATED_SCALING_GUIDE.md)**
