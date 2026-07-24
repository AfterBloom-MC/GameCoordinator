### Automated Scaling and Server Duplication Guide

Moving to a system that allows for "duplicated" (cloned) servers to automate scaling is highly feasible with the current architecture. Since the system is already stateless and relies on Redis/MySQL, the primary challenge is **Identity Management** and **Dynamic Configuration**.

---

### 1. Moving to Dynamic Identity
Currently, `serverId` is often manually set in `config.yml`. To support duplication (e.g., spinning up 10 identical containers/VMs), identities must be generated at runtime.

#### Coordinator Scaling
- **Problem**: Every coordinator needs a unique `serverId` (e.g., `coord-01`, `coord-02`).
- **Solution**: 
    - Use **Environment Variables** (e.g., `COORDINATOR_ID`) passed by the orchestrator (Kubernetes, Docker, Pterodactyl).
    - If no ID is provided, generate a random suffix: `coord-` + `UUID.randomUUID().toString().substring(0, 8)`.
    - Modify `GameCoordinator.java` to check for these variables before reading `config.yml`.

#### Game Server Scaling
- **Problem**: Cloned game servers must not share the same `senderId`.
- **Solution**:
    - **Cloud-Init/Startup Scripts**: Use a startup script that fetches the instance name from the cloud provider (AWS Instance ID, K8s Pod Name) and writes it to the server's `config.yml` before starting the JAR.
    - **Self-Generated IDs**: On boot, if `senderId` is generic (like `game-template`), the server should generate a unique name (e.g., `bedwars-A7F2`) and use that for all heartbeats.

---

### 2. Automated Discovery and Load Balancing
The system already uses a "Pull-based" discovery (Heartbeats). No manual registration is needed.

- **Scale-Up**: When a new server starts, it sends a `heartbeat`. All coordinators see it and add it to their `Servers` registry.
- **Scale-Down**: When a server is deleted, it stops sending heartbeats. Coordinators will automatically `prune()` it after the timeout (default 30-60s).
- **Matchmaking**: To automate scaling, a "Scaling Controller" (external script or coordinator task) should monitor the `lobbyCount` or `playerCount` for a game type. 
    - *If `total_lobbies < 2`, trigger API call to start new server.*
    - *If `empty_lobbies > 5`, trigger API call to drain and shut down a server.*

---

### 3. Avoiding Resource Contention
When duplicating servers, certain resources must be managed carefully:

1.  **MySQL Connections**: 
    - 50 cloned servers = 50 connection pools. 
    - **Solution**: Use **ProxySQL** or **RDS Proxy** as a central point for all servers to connect to. This prevents hitting the `max_connections` limit on the database.
2.  **Redis Pub/Sub Volume**:
    - Every duplicated server receives every message.
    - **Solution**: Move to **Redis Streams** with **Consumer Groups**. This allows you to "duplicate" coordinators while ensuring that a message (like a stats update) is only processed by **one** of them.
3.  **Discord Webhooks**:
    - As noted in `SCALABILITY_AND_IMPROVEMENTS.md`, implementation of **Leader Election** is mandatory before duplicating coordinators to prevent spam.

---

### 4. Implementation Steps for Automation
1.  **Containerization**: Move Coordinator and Game Servers to Docker.
2.  **Externalize Config**: Use a central configuration store (Redis Hash or Consul) or Environment Variables instead of static `config.yml` files.
3.  **Graceful Shutdown**: Ensure servers send a `disconnect` message on shutdown so the registry is updated instantly instead of waiting for a timeout.
4.  **Health Check Endpoint**: Add a small HTTP server (e.g., Javalin) to the Coordinator so Kubernetes/Docker can monitor if the node is "Ready" or "Healthy".

### Summary of Effort
The system is **85% ready**. The remaining **15%** involves replacing static config values with environment/dynamic variables and implementing a Leader Election for Discord/Global tasks.
