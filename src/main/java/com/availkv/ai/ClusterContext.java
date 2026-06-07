package com.availkv.ai;

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
        sb.append("Timestamp      : ").append(Instant.now()).append("\n");
        sb.append("This Node      : ").append(clusterManager.getNodeId()).append("\n");
        sb.append("Current State  : ").append(clusterManager.getState()).append("\n");
        sb.append("Current Term   : ").append(clusterManager.getCurrentTerm()).append("\n");

        String leader = clusterManager.getLeaderId();
        sb.append("Known Leader   : ").append(leader != null ? leader : "UNKNOWN — election may be in progress").append("\n");

        sb.append("Total Keys     : ").append(kvStore.size()).append("\n");

        long msSinceHeartbeat = System.currentTimeMillis() - clusterManager.getLastHeartbeatTimestamp();
        sb.append("Last Heartbeat : ").append(msSinceHeartbeat).append("ms ago\n");

        // Flag potential issues so the LLM has explicit signals to reason about
        if (msSinceHeartbeat > 4000 && !clusterManager.isLeader()) {
            sb.append("⚠ WARNING       : Heartbeat delayed — leader may be unreachable\n");
        }
        if (leader == null) {
            sb.append("⚠ WARNING       : No leader known — cluster may be mid-election\n");
        }

        // Last 10 WAL entries give the LLM recent write history
        sb.append("\nRecent WAL entries (last 10):\n");
        List<WriteOperation> ops = walManager.readAll();
        int start = Math.max(0, ops.size() - 10);
        if (ops.isEmpty()) {
            sb.append("  (no writes yet)\n");
        } else {
            for (WriteOperation op : ops.subList(start, ops.size())) {
                sb.append("  ").append(op).append("\n");
            }
        }

        sb.append("\nPeer URLs      : ").append(String.join(", ", clusterManager.getPeerUrls())).append("\n");
        sb.append("===========================================\n");

        return sb.toString();
    }
}