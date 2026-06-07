package com.availkv.ai;

import com.availkv.cluster.ClusterEvent;
import com.availkv.cluster.ClusterManager;
import com.availkv.replication.WriteOperation;
import com.availkv.storage.KVStore;
import com.availkv.storage.WALManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
public class ClusterContext {

    private static final long UNREACHABLE_THRESHOLD_MS = 6000;

    private final ClusterManager clusterManager;
    private final KVStore kvStore;
    private final WALManager walManager;

    public ClusterContext(ClusterManager clusterManager,
                          KVStore kvStore,
                          WALManager walManager) {
        this.clusterManager = clusterManager;
        this.kvStore = kvStore;
        this.walManager = walManager;
    }

    public String build() {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();

        // Basic state
        sb.append("=== AvailKV Cluster Diagnostic Context ===\n");
        sb.append("Snapshot time  : ").append(Instant.now()).append("\n");
        sb.append("This Node      : ").append(clusterManager.getNodeId()).append("\n");
        sb.append("Current State  : ").append(clusterManager.getState()).append("\n");
        sb.append("Current Term   : ").append(clusterManager.getCurrentTerm()).append("\n");
        sb.append("Known Leader   : ")
                .append(clusterManager.getLeaderId() != null
                        ? clusterManager.getLeaderId()
                        : "NONE — no leader currently known")
                .append("\n");
        sb.append("Total Keys     : ").append(kvStore.size()).append("\n");

        long msSince = now - clusterManager.getLastHeartbeatTimestamp();
        sb.append("Last Heartbeat : ").append(msSince).append("ms ago\n\n");

        // Node reachability — explicit per-node status
        sb.append("NODE REACHABILITY:\n");
        // This node is always reachable (it's responding right now)
        sb.append("  ").append(clusterManager.getNodeId())
                .append(" (this node) : REACHABLE\n");

        Map<String, Long> lastSeen = clusterManager.getPeerLastSeen();
        for (String peerUrl : clusterManager.getPeerUrls()) {
            peerUrl = peerUrl.trim();
            if (peerUrl.isEmpty()) continue;

            Long lastContact = lastSeen.get(peerUrl);
            if (lastContact == null) {
                sb.append("  ").append(peerUrl)
                        .append(" : UNKNOWN — never successfully contacted since this node started\n");
            } else {
                long ago = now - lastContact;
                boolean reachable = ago < UNREACHABLE_THRESHOLD_MS;
                sb.append("  ").append(peerUrl)
                        .append(" : ")
                        .append(reachable ? "REACHABLE" : "UNREACHABLE")
                        .append(" (last contact ").append(ago).append("ms ago)\n");
            }
        }
        sb.append("\n");

        // Vote records
        sb.append("VOTE RECORDS (per term):\n");
        Map<Integer, Map<String, String>> voteLog = clusterManager.getVoteLog();
        if (voteLog.isEmpty()) {
            sb.append("  No votes recorded yet.\n");
        } else {
            // Sort terms so latest is last
            new TreeMap<>(voteLog).forEach((term, votes) -> {
                sb.append("  Term ").append(term).append(":\n");
                votes.forEach((voter, candidate) ->
                        sb.append("    ").append(voter)
                                .append(" voted for ").append(candidate).append("\n")
                );
            });
        }
        sb.append("\n");

        // 4. Pre-computed explicit facts (saves LLM from inferring)
        sb.append("EXPLICIT FACTS:\n");

        // Who is the leader and how did they get elected
        String leader = clusterManager.getLeaderId();
        int term = clusterManager.getCurrentTerm();
        if (leader != null) {
            Map<String, String> currentTermVotes = clusterManager.getVotesForTerm(term);
            long voteCount = currentTermVotes.values().stream()
                    .filter(c -> c.equals(leader)).count();
            sb.append("  - Current leader is ").append(leader)
                    .append(" elected in term ").append(term)
                    .append(" with ").append(voteCount).append(" vote(s).\n");

            if (!currentTermVotes.isEmpty()) {
                List<String> supporters = new ArrayList<>();
                currentTermVotes.forEach((voter, candidate) -> {
                    if (candidate.equals(leader)) supporters.add(voter);
                });
                sb.append("  - Nodes that voted for ").append(leader).append(": ")
                        .append(String.join(", ", supporters)).append(".\n");
            }
        }

        // Unreachable node facts
        boolean anyUnreachable = false;
        for (String peerUrl : clusterManager.getPeerUrls()) {
            peerUrl = peerUrl.trim();
            Long lastContact = lastSeen.get(peerUrl);
            if (lastContact == null || (now - lastContact) >= UNREACHABLE_THRESHOLD_MS) {
                sb.append("  - ").append(peerUrl)
                        .append(" is currently UNREACHABLE. It has not responded to heartbeats")
                        .append(lastContact == null ? " at all since startup" :
                                " for " + (now - lastContact) + "ms")
                        .append(". This node is likely down or disconnected.\n");
                anyUnreachable = true;
            }
        }
        if (!anyUnreachable) {
            sb.append("  - All peer nodes are currently reachable.\n");
        }

        // Heartbeat warning
        if (msSince > 4000 && !clusterManager.isLeader()) {
            sb.append("  - ⚠ This node has not received a heartbeat for ")
                    .append(msSince).append("ms (expected every 2000ms). ")
                    .append("Leader may be down.\n");
        }
        sb.append("\n");

        // 5. Event history
        sb.append("CLUSTER EVENT HISTORY (most recent last):\n");
        List<ClusterEvent> events = clusterManager.getRecentEvents(15);
        if (events.isEmpty()) {
            sb.append("  (no events yet)\n");
        } else {
            for (ClusterEvent e : events) {
                sb.append("  ").append(e).append("\n");
            }
        }
        sb.append("\n");

        // 6. Recent WAL writes
        sb.append("RECENT WRITES (last 10 WAL entries):\n");
        List<WriteOperation> ops = walManager.readAll();
        if (ops.isEmpty()) {
            sb.append("  (no writes yet)\n");
        } else {
            int start = Math.max(0, ops.size() - 10);
            for (WriteOperation op : ops.subList(start, ops.size())) {
                sb.append("  ").append(op).append("\n");
            }
        }

        sb.append("===========================================\n");
        return sb.toString();
    }
}