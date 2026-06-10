<p align="center">
  <img src="src/main/resources/static/logo.png" alt="AvailKV Logo" width="520"/>
</p>

<p align="center">
  <b>A distributed in-memory key-value store built from scratch in Java & Spring Boot</b><br/>
  Raft-style leader election · WAL durability · AI-assisted diagnostics · Docker orchestration
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=flat-square&logo=openjdk"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen?style=flat-square&logo=springboot"/>
  <img src="https://img.shields.io/badge/Docker-Compose-blue?style=flat-square&logo=docker"/>
  <img src="https://img.shields.io/badge/Ollama-gemma2%3A2b-purple?style=flat-square"/>
  <img src="https://img.shields.io/badge/CAP%20Theorem-Availability%20+%20Partition%20Tolerance-red?style=flat-square"/>
</p>


## What is AvailKV?

AvailKV is a personal systems project built to explore and demonstrate core distributed systems concepts hands-on — not by configuring an existing framework, but by implementing the mechanics from scratch.

It implements a **Raft-inspired consensus protocol** on top of Spring Boot, with a full cluster management CLI, WAL-based crash recovery, and an AI diagnostic layer powered by a local LLM. The cluster supports 3 to 11 nodes (odd numbers only, for quorum) and has been tested under intentional fault injection — leader kills, partial failures, and network delays in Docker.

It prioritizes **Availability and Partition Tolerance (AP)** from the CAP theorem: reads are always served from any alive node, even under leader failure, accepting eventual consistency as the deliberate tradeoff. Writes are held to a stricter standard — only accepted by a quorum-elected leader, preventing stale or split-brain writes entirely.



## Demo

![Demo](src/main/resources/static/demo.gif)



## Table of Contents

- [Architecture](#architecture)
- [Core Components](#core-components)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Performance Characteristics](#performance-characteristics)
- [Getting Started](#getting-started)
  - [Local Mode](#local-mode)
  - [Docker Mode](#docker-mode)
- [CLI Commands](#cli-commands)
- [AI Diagnostics](#ai-diagnostics)
- [Raft Safety Guarantees](#raft-safety-guarantees)
- [Consistency Model](#consistency-model-important)
- [Design Decisions & Tradeoffs](#design-decisions--tradeoffs)
- [License](#license)


## Architecture

AvailKV follows a **Leader–Follower architecture** inspired by the Raft consensus model.

In a 3-node cluster:
- 1 node acts as the **Leader**
- 2 nodes act as **Followers**

In a 5-node cluster, quorum requires **3 votes**. In a 7-node cluster, **4 votes**. This math is enforced at election time — a candidate that cannot reach a majority simply does not become leader.

#### Write-Ahead Logging (WAL)

Every mutation is written to disk before being applied to the in-memory store. On restart, the WAL is replayed in full — the node recovers its state independently, without contacting peers.

```
                    ┌─────────────────────┐
                    │    Client / CLI     │
                    │ avail.sh / docker   │
                    └──────────┬──────────┘
                               │ HTTP
                               ▼
                    ┌─────────────────────┐
                    │        node1        │
                    │       LEADER        │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │         WAL         │
                    └──────────┬──────────┘
                               │
                               ▼
                    ┌─────────────────────┐
                    │   In-Memory State   │
                    └──────────┬──────────┘
                               │
                    Replication│
                     ┌─────────┴─────────┐
                     ▼                   ▼
            ┌─────────────────┐ ┌─────────────────┐
            │      node2      │ │      node3      │
            │    FOLLOWER     │ │    FOLLOWER     │
            └────────┬────────┘ └────────┬────────┘
                     │                   │
                     ▼                   ▼
              ┌───────────┐       ┌───────────┐
              │    WAL    │       │    WAL    │
              └───────────┘       └───────────┘
```

**Write path:** Client → Leader → WAL → Memory → Replicate to followers

**Read path:** Try Leader → if unreachable, query alive Followers → return first available result *(stale reads possible, and intentional)*



## Core Components

| Component | Responsibility |
|---|---|
| `KVStore` | Thread-safe in-memory storage using `ConcurrentHashMap` |
| `ClusterManager` | Node state machine — term, leader, votes, event log, peer reachability |
| `HeartbeatScheduler` | Sends heartbeats every 2s (leader) / triggers election on timeout (follower) |
| `PeerClient` | OkHttp-based inter-node RPC — heartbeat, vote, replicate |
| `ReplicationService` | Write pipeline — WAL → local apply → fan-out to followers |
| `WALManager` | Appends every mutation to disk, replays on startup for crash recovery |
| `ClusterContext` | Assembles live cluster snapshot into structured text for LLM consumption |
| `OllamaClient` | Streams prompt + context to local Ollama, parses chunked response |



## Features

**Distributed consensus**
- Term-based leader election with majority quorum voting
- Randomised election timeouts (5–8s) to prevent split votes
- Automatic failover when leader becomes unreachable — new leader elected within one timeout window
- Nodes step down immediately on seeing a higher term

**Durability**
- Write-Ahead Log — every mutation logged to disk before applying to memory
- Full WAL replay on node restart — state restored without contacting peers
- Each node maintains its own WAL independently

**AI diagnostics**
- `POST /ask` endpoint accepts natural language questions about the cluster
- `ClusterContext` builds a rich, grounded prompt: node states, event history, vote records, peer reachability by name, recent writes
- Strict prompt rules prevent the LLM from hallucinating — answers are constrained to real cluster data only

**Cluster management CLI**
- Interactive REPL with session persistence across restarts
- Supports 3 to 11 nodes (odd numbers only for quorum)
- Two modes: local (`avail.sh`) and Docker (`docker-avail.sh`)
- Docker Compose file generated dynamically based on chosen node count



## Tech Stack

| Layer | Technology |
|---|---|
| Language & Runtime | Java 21 |
| Web Framework | Spring Boot 3.2 (REST API, scheduling, DI) |
| Inter-node RPC | OkHttp (HTTP/1.1) |
| Serialization | Jackson |
| AI / LLM | Ollama + gemma2:2b (local inference) |
| Containerization | Docker + Docker Compose |
| CLI | Bash (REPL, cluster orchestration) |



## Performance Characteristics

These are informal benchmarks from local testing, included to give a sense of the system's behaviour rather than as production guarantees.

| Scenario | Observed behaviour |
|---|---|
| Leader election after failure | New leader elected within ~6s (one full timeout window) |
| WAL replay on restart | ~1,000 entries restored in under 200ms |
| Read latency from leader | Sub-10ms under normal conditions |
| Read latency from follower (fallback) | Sub-15ms — follower may return stale data |
| Heartbeat interval | Every 2s (leader → followers) |
| Election timeout range | 5–8s (randomised per node to prevent split votes) |

*Tested on a 5-node local cluster (16 GB RAM). Docker mode introduces additional latency due to container networking — see [the Docker warning below](#docker-mode).*



## Getting Started

### Local mode

#### 1. Install Ollama

Install Ollama from [https://ollama.com](https://ollama.com)

#### 2. Pull the AI model

> **gemma2:2b** is a lightweight LLM that runs efficiently on most modern CPUs, making a dedicated GPU optional for local development and testing.

```bash
ollama pull gemma2:2b
```

#### 3. Build AvailKV

```bash
mvn clean package -DskipTests
```

#### 4. Start the cluster

```bash
bash avail.sh
```

The script asks for node count (3 / 5 / 7 / 9 / 11), starts all nodes as background processes, waits for them to be healthy, and drops into the REPL.

### Docker mode

```bash
# Start the cluster
bash docker-avail.sh

# Pull the Ollama model (first time only — persists across restarts)
docker exec -it availkv-ollama ollama pull gemma2:2b
```

The script generates `docker-compose.generated.yml` dynamically, builds the image, and starts all containers.

> ⚠️ **Docker timing caveat**
>
> AvailKV uses HTTP for inter-node communication (heartbeats, elections, replication). In Docker, container networking and scheduling occasionally introduce latency that can affect consensus timing — since Raft-style protocols are sensitive to message delays, you may observe slower elections or transient misbehaviour.
>
> This is expected. Production distributed systems typically use gRPC with carefully tuned timeouts and backoff strategies to reduce this sensitivity. If you observe unexpected behaviour in Docker mode, container networking is the most likely cause.



## CLI Commands

```
PUT key=value         Write a key to the leader
GET key               Read — tries leader first, falls back to any alive node
DELETE key            Delete a key via the leader
SYSTEMSTATUS          Show all nodes with roles and up/down status
LISTALL               Print full WAL — leader first, fallback to alive follower
LEADER                Show current leader, term, and URL
KILL <n>              Kill node n  (e.g. KILL 2)
KILL LEADER           Kill whichever node is currently leader
KILL ALL              Kill all nodes
RESTART <n>           Restart node n
AI <question>         Ask the leader an AI diagnostic question
AI <n> <question>     Ask node n specifically
LOGS <n>              Show last 50 log lines from node n  (Docker mode only)
HELP                  Show all commands
EXIT                  Prompts to persist state or reset everything
```



## AI Diagnostics

The `/ask` endpoint accepts plain-text questions and answers using live cluster state as context. The LLM never speculates — answers are grounded strictly in what the cluster actually knows at the time of the request.

```bash
availkv> AI Is the cluster healthy right now?
availkv> AI Why did the last election happen?
availkv> AI Who voted for the current leader?
availkv> AI What is the status of node2?
availkv> AI 3 What does this node know about the cluster?
```

Context fed to the LLM includes: node identity and state, current term, leader, peer reachability by node name, vote records per term, recent WAL entries, cluster event history (elections, step-downs, vote grants/rejections), and pre-computed facts so the model doesn't need to infer from raw data.



## Raft Safety Guarantees

To prevent split-brain scenarios, a new leader can only be elected if a majority (quorum) of nodes is available.

| Cluster size | Quorum required | Max tolerated failures |
|---|---|---|
| 3 nodes | 2 votes | 1 node |
| 5 nodes | 3 votes | 2 nodes |
| 7 nodes | 4 votes | 3 nodes |
| 9 nodes | 5 votes | 4 nodes |
| 11 nodes | 6 votes | 5 nodes |

If the current leader fails and fewer than the required quorum of nodes remain reachable, no candidate can obtain a majority. As a result, **leader election does not succeed**, no leader is chosen, and the cluster rejects all write requests until quorum is restored. Reads, however, continue to be served by any alive follower.

🟡🟢 **This behaviour is intentional — it ensures that only one valid leader can exist at any time, preventing split-brain conditions.**



## Consistency Model (Important)

AvailKV has **asymmetric consistency guarantees** — reads and writes are treated very differently by design.

### Write consistency — strong

- All writes go exclusively through the **Leader**, which is the single source of truth.
- A leader can only be elected with a **majority quorum**, ensuring it is never a stale or behind node.
- If a majority quorum is unavailable, **no leader is elected** and all writes are rejected until quorum is restored.
- Writes can never be accepted by a stale node or during a split-brain scenario.

### Read consistency — available (best-effort)

- The cluster always tries the **Leader first** for reads.
- If the Leader is unreachable, the request **falls back to any alive follower**, even if only a single node remains up.
- Reads from followers may return **stale data**, since replication may not have completed.
- As long as at least one node is alive, reads are served. The cluster only goes fully dark if every node is down.

### Behaviour summary

| Scenario | Read | Write |
|---|---|---|
| All nodes healthy | ✅ Fresh data from leader | ✅ Accepted by leader |
| Leader down, quorum alive | ⚠️ Stale data from follower (until new leader elected) | ⏳ Blocked until new leader elected |
| Leader down, quorum lost | ⚠️ Stale data from any alive follower | ❌ Rejected — no leader can be elected |
| Single node alive | ⚠️ Stale data from that node | ❌ Rejected — no quorum |
| All nodes down | ❌ No response | ❌ No response |

### Consistency properties

| Property | Status | Notes |
|---|---|---|
| Write safety (no stale leader) | ✅ Yes | Leader always elected with majority quorum |
| Write availability under quorum loss | ❌ No | Intentional — prevents split-brain |
| Read availability | ✅ Yes | Reads served as long as any node is alive |
| Strong read consistency | ❌ No | Reads may be stale from followers |
| Eventual consistency (normal operation) | ⚠️ Partial | Converges when all nodes are healthy |
| Eventual consistency (after node rejoin) | ❌ No | No anti-entropy / re-sync for missed writes |

> Conflicts are easy to reason about because writes are only ever accepted by a quorum-elected leader — a node that lagged behind can never become write authority. Divergence is limited to read-staleness only; write-divergence is impossible.

### Real-life use case

AvailKV's asymmetric consistency maps well to use cases like caching layers, social media counters, leaderboards, or configuration reads — where reads must always respond, writes must be correct, but the read value doesn't need to be millisecond-fresh.

For example, in a "like counter" system:

- A user likes a post and the write is accepted by the leader, then replicated.
- If one node is temporarily offline, it may miss the update.
- A read from that lagging node returns a slightly older count — but it always responds instead of failing.
- A new "like" write is only accepted once a valid leader with quorum exists, so the counter never goes backwards or forks across nodes.



## Design Decisions & Tradeoffs

These are the deliberate engineering choices made in AvailKV, and what I'd change in a production version.

**HTTP over gRPC for inter-node RPC**
Spring Boot's HTTP stack was the fastest path to a working cluster. In a production system, gRPC would be a better fit — it's lower latency, supports bidirectional streaming (useful for replication), and has cleaner connection management. The Docker timing issues AvailKV exhibits are partly a consequence of HTTP overhead.

**No anti-entropy / re-sync after rejoin**
When a node that missed writes rejoins the cluster, it does not currently catch up from peers — it only replays its own WAL. A proper implementation would include a log reconciliation step (similar to Raft's `AppendEntries` with a `prevLogIndex` check) so rejoining nodes converge to the leader's state. This is the most significant gap from real Raft.

**Local LLM for AI diagnostics**
Using Ollama + gemma2:2b keeps the diagnostic layer fully local and private, with no external API calls. The tradeoff is that the model is small and constrained — it works well for factual cluster Q&A but isn't capable of deeper reasoning. In a production observability tool you'd likely use a larger model or a fine-tuned one.

**Randomised election timeouts**
The 5–8s range was chosen empirically — wide enough to prevent most split votes in a local environment, but not so wide that failover feels slow. A real system would tune this based on measured network RTT and heartbeat jitter.



## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.



> **Note:** AvailKV was built as a personal systems project to explore distributed consensus, fault tolerance, WAL-based durability, and AI-assisted observability hands-on. It implements many ideas inspired by production systems, but is not intended to be production-ready software.