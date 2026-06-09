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
  <img src="https://img.shields.io/badge/CAP%20Theorem-AP%20Read%20System-red?style=flat-square"/>
</p>



## What is AvailKV?

AvailKV is a distributed in-memory key-value store built for learning and demonstrating core distributed systems concepts. It implements a Raft-inspired consensus protocol from scratch, on top of Spring Boot, with a full CLI for cluster management, WAL-based crash recovery, and an AI diagnostic layer powered by a local LLM.

It prioritizes **Availability** and **Partition Tolerance** (AP) from the CAP theorem, the cluster keeps serving reads from any alive node even during leader failure, accepting eventual consistency as the tradeoff. Writes, however, are held to a stricter standard: they are only accepted by a quorum-elected leader, preventing stale or split-brain writes.



## Table of Contents

- [Architecture](#architecture)

- [Core Components](#core-components)

- [Features](#features)

- [Tech Stack](#tech-stack)

- [Getting Started](#getting-started)
  - [Local Mode](#local-mode)
  - [Docker Mode](#docker-mode)

- [CLI Commands](#cli-commands)

- [AI Diagnostics](#ai-diagnostics)

- [Raft Safety Guarantees](#raft-safety-guarantees)

- [Consistency Model (Important)](#consistency-model-important)

- [License](#license)




## Architecture

AvailKV follows a **Leader–Follower architecture** inspired by the Raft consensus model.

In a 3-node cluster:
- 1 node acts as the **Leader**
- 2 nodes act as **Followers**

#### Write-Ahead Logging (WAL)

AvailKV uses **Write-Ahead Logging (WAL)** to ensure durability. Every change is first recorded in a log file before being applied to the in-memory store, allowing data recovery after crashes or restarts.

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

**Read path:** Try Leader → if unreachable, query alive Followers → return first available result (⚠️ stale reads possible)


## Core Components

| Component | Responsibility |
|---|---|
| `KVStore` | Thread-safe in-memory storage using `ConcurrentHashMap` |
| `ClusterManager` | Node state machine — term, leader, votes, event log, peer reachability |
| `HeartbeatScheduler` | Sends heartbeats every 2s (leader) / triggers election on timeout (follower) |
| `PeerClient` | OkHttp-based inter-node RPC — heartbeat, vote, replicate |
| `ReplicationService` | Write pipeline — WAL → local apply → fan-out to followers |
| `WALManager` | Appends every mutation to disk, replays on startup for crash recovery |
| `ClusterContext` | Assembles live cluster snapshot into text for LLM consumption |
| `OllamaClient` | Streams prompt + context to local Ollama, parses chunked response |


## Features

**Distributed consensus**
- Term-based leader election with majority quorum voting
- Randomised election timeouts (5–8s) to prevent split votes
- Automatic failover when leader becomes unreachable
- Nodes step down immediately on seeing a higher term

**Durability**
- Write-Ahead Log — every mutation logged to disk before applying to memory
- Full WAL replay on node restart — state restored without contacting peers
- Each node maintains its own WAL independently

**AI diagnostics**
- `POST /ask` endpoint accepts natural language questions about the cluster
- `ClusterContext` builds a rich prompt: node states, event history, vote records, peer reachability by name, recent writes
- Strict prompt rules prevent the LLM from hallucinating — answers are grounded in real cluster data

**Cluster management CLI**
- Interactive REPL with session persistence across restarts
- Supports 3 to 11 nodes (odd numbers only for quorum)
- Two modes: local (`avail.sh`) and Docker (`docker-avail.sh`)
- Docker compose file generated dynamically based on chosen node count



## Tech Stack

- **Java 21** + **Spring Boot 3.2** — REST API, scheduling, dependency injection
- **OkHttp** — inter-node HTTP communication
- **Jackson** — JSON serialization
- **Ollama** + **gemma2:2b** — local LLM for AI diagnostics
- **Docker** + **Docker Compose** — containerized multi-node deployment
- **Shell (Bash)** — cluster management CLI



## Getting Started

### Local mode

#### 1. Install Ollama

Install Ollama from:

https://ollama.com


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

The `/ask` endpoint accepts plain-text questions and answers using live cluster state as context. The LLM never speculates — answers are grounded strictly in what the cluster actually knows.

```bash
availkv> AI Is the cluster healthy right now?
availkv> AI Why did the last election happen?
availkv> AI Who voted for the current leader?
availkv> AI What is the status of node2?
availkv> AI 3 What does this node know about the cluster?
```

Context fed to the LLM includes: node identity and state, current term, leader, peer reachability by node name, vote records per term, recent WAL entries, cluster event history (elections, step-downs, vote grants/rejections), and explicit pre-computed facts so the model doesn't have to infer from raw data.



## Raft Safety Guarantees

To prevent split-brain scenarios, a new leader can only be elected if a majority (quorum) of nodes is available.

For example, in a 5-node cluster:

* A quorum requires at least **3 nodes**.
* If the current leader fails and fewer than 3 nodes remain reachable, no candidate can obtain a majority of votes.
* As a result, **leader election does not succeed**, no leader is chosen, and the cluster rejects all write requests until a quorum is restored.
* Reads, however, can still be served by any alive follower.

🟡🟢 **This behavior is intentional and ensures that only one valid leader can exist at a time, preventing split-brain conditions.**


## Consistency Model (Important)

AvailKV has **asymmetric consistency guarantees** — reads and writes are treated very differently by design.

### Write Consistency — Strong

Writes in AvailKV are strongly controlled:

- All writes go exclusively through the **Leader**, which is the single source of truth.
- A leader can only be elected with a **majority quorum**, ensuring it is never a stale or behind node.
- If a majority quorum is unavailable, **no leader is elected** and all write requests are rejected until quorum is restored.
- This means writes can never be accepted by a stale node or during a split-brain scenario.

### Read Consistency — Available (Best-Effort)

Reads in AvailKV prioritize availability over strict accuracy:

- The cluster always tries the **Leader first** for a read.
- If the Leader is unreachable, the request **falls back to any alive follower** — even if only a single node remains up.
- As long as at least one node is alive, reads will be served. The system only goes completely dark if every node is down.
- Reads from followers may return **stale data**, since replication from the leader may not have completed.

### Behavior Summary

| Scenario | Read | Write |
|---|---|---|
| All nodes healthy | ✅ Fresh data from leader | ✅ Accepted by leader |
| Leader down, quorum alive | ⚠️ Stale data from follower (until new leader elected) | ⏳ Blocked until new leader elected |
| Leader down, quorum lost | ⚠️ Stale data from any alive follower | ❌ Rejected — no leader can be elected |
| Single node alive | ⚠️ Stale data from that node | ❌ Rejected — no quorum |
| All nodes down | ❌ No response | ❌ No response |

### Consistency Properties

| Property | Status | Notes |
|----------|--------|-------|
| Write safety (no stale leader) | ✅ Yes | Leader always elected with majority quorum |
| Write availability under quorum loss | ❌ No | Writes blocked — intentional to prevent split-brain |
| Read availability | ✅ Yes | Reads served as long as any node is alive |
| Strong read consistency | ❌ No | Reads may be stale, especially from followers |
| Eventual consistency (normal operation) | ⚠️ Partial | Converges when all nodes are healthy |
| Eventual consistency (after node rejoin) | ❌ No | No anti-entropy / re-sync for missed writes |

> Conflicts are easy to reason about because writes are only ever accepted by a quorum-elected leader — a node that lagged behind can never become the write authority. This limits divergence to read-staleness only, never write-divergence.

### Design Goal

AvailKV is designed so that **reads never fail due to partial failures**, while **writes are never accepted in an unsafe state**. The system trades read freshness for read availability, but never trades write safety for write availability.

This makes AvailKV well suited for scenarios where:
- High read availability is critical, even under node failures
- Slightly stale reads are acceptable
- Write correctness and prevention of split-brain are non-negotiable
- Partition tolerance is required

### Real-life Use Case

AvailKV's asymmetric consistency is suitable for use cases like caching layers, social media counters, leaderboards, or configuration reads — where reads must always respond, writes must be correct, but the read value doesn't need to be millisecond-fresh.

For example, in a "like counter" system:

- A user likes a post and the write is accepted by the leader, then replicated.
- If one node is temporarily offline, it may miss the update.
- A read from that lagging node returns a slightly older count — but it always responds instead of failing.
- Meanwhile, a new "like" write is only accepted once a valid leader with quorum exists, so the counter never goes backwards or forks across nodes.



## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.