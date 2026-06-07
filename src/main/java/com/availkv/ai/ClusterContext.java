package com.availkv.ai;

import com.availkv.cluster.ClusterEvent;
import com.availkv.cluster.ClusterManager;
import com.availkv.replication.WriteOperation;
import com.availkv.storage.KVStore;
import com.availkv.storage.WALManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ClusterContext {

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

        sb.append("=== AvailKV Cluster Diagnostic Context ===\n");
        sb.append("Snapshot time  : ").append(Instant.now()).append("\n");
        sb.append("This Node      : ").append(clusterManager.getNodeId()).append("\n");
        sb.append("Current State  : ").append(clusterManager.getState()).append("\n");
        sb.append("Current Term   : ").append(clusterManager.getCurrentTerm()).append("\n");

        String leader = clusterManager.getLeaderId();
        sb.append("Known Leader   : ")
                .append(leader != null ? leader : "NONE — election may be in progress")
                .append("\n");

        sb.append("Total Keys     : ").append(kvStore.size()).append("\n");

        long msSince = System.currentTimeMillis() - clusterManager.getLastHeartbeatTimestamp();
        sb.append("Last Heartbeat : ").append(msSince).append("ms ago\n");

        // Explicit warnings as facts — not left for the LLM to infer
        if (msSince > 4000 && !clusterManager.isLeader()) {
            sb.append("⚠ FACT: Heartbeat has not been received for ")
                    .append(msSince).append("ms. ")
                    .append("This node expected one every 2000ms. ")
                    .append("Leader may be down or unreachable.\n");
        }
        if (leader == null) {
            sb.append("⚠ FACT: No leader is currently known. Cluster is leaderless.\n");
        }

        sb.append("\nCluster event history (most recent last):\n");
        List<ClusterEvent> events = clusterManager.getRecentEvents(15);
        if (events.isEmpty()) {
            sb.append("  (no events recorded yet — node just started)\n");
        } else {
            for (ClusterEvent e : events) {
                sb.append("  ").append(e).append("\n");
            }
        }

        sb.append("\nRecent WAL entries (last 10 writes):\n");
        List<WriteOperation> ops = walManager.readAll();
        if (ops.isEmpty()) {
            sb.append("  (no writes yet)\n");
        } else {
            int start = Math.max(0, ops.size() - 10);
            for (WriteOperation op : ops.subList(start, ops.size())) {
                sb.append("  ").append(op).append("\n");
            }
        }

        sb.append("\nPeer URLs      : ")
                .append(String.join(", ", clusterManager.getPeerUrls()))
                .append("\n");
        sb.append("===========================================\n");

        return sb.toString();
    }
}