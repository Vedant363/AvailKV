package com.availkv.replication;

import com.availkv.client.PeerClient;
import com.availkv.cluster.ClusterManager;
import com.availkv.storage.KVStore;
import com.availkv.storage.WALManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    private final okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final KVStore kvStore;
    private final WALManager walManager;
    private final PeerClient peerClient;
    private final ClusterManager clusterManager;

    public ReplicationService(KVStore kvStore, WALManager walManager,
                              PeerClient peerClient, ClusterManager clusterManager) {
        this.kvStore = kvStore;
        this.walManager = walManager;
        this.peerClient = peerClient;
        this.clusterManager = clusterManager;
    }

    public void put(String key, String value, boolean replicated) {
        WriteOperation op = new WriteOperation(WriteOperation.Type.PUT, key, value);
        execute(op, replicated);
    }

    public void delete(String key, boolean replicated) {
        WriteOperation op = new WriteOperation(WriteOperation.Type.DELETE, key, null);
        execute(op, replicated);
    }

    /**
     * Actively checks reachability of all peers at write time.
     * Does NOT rely on peerLastSeen timestamps — those reflect the last
     * successful heartbeat but don't expire when a node goes down.
     *
     * Instead, attempts a live /actuator/health call to each peer
     * right now and counts responses within a 2s timeout.
     */
    private void checkWriteQuorum() {
        String[] peerUrls = clusterManager.getPeerUrls();
        int totalNodes = peerUrls.length + 1; // peers + self
        int reachable = 1; // self is always reachable

        for (String peerUrl : peerUrls) {
            peerUrl = peerUrl.trim();
            if (peerUrl.isEmpty()) continue;
            try {
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(peerUrl + "/actuator/health")
                        .build();
                try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        reachable++;
                    }
                }
            } catch (Exception e) {
                // Peer unreachable — does not count
                log.debug("Quorum check: {} unreachable — {}", peerUrl, e.getMessage());
            }
        }

        int required = totalNodes / 2 + 1;
        log.info("Quorum check: {}/{} reachable (need {})", reachable, totalNodes, required);

        if (reachable < required) {
            throw new QuorumNotAvailableException(
                    "Write rejected — quorum not available. " +
                            "Reachable: " + reachable + "/" + totalNodes +
                            ", need: " + required
            );
        }
    }

    private void execute(WriteOperation op, boolean replicated) {
        log.info("[{}] Executing {} (replicated={})",
                clusterManager.getNodeId(), op, replicated);

        if (!replicated) {
            checkWriteQuorum();
        }

        // WAL first
        walManager.append(op);

        // Apply to memory
        applyToStore(op);

        // Fan-out (leader only, not a replicated write)
        if (!replicated && clusterManager.isLeader()) {
            fanOutToPeers(op);
        }
    }

    private void applyToStore(WriteOperation op) {
        switch (op.getType()) {
            case PUT    -> kvStore.put(op.getKey(), op.getValue());
            case DELETE -> kvStore.delete(op.getKey());
        }
    }

    private void fanOutToPeers(WriteOperation op) {
        for (String peerUrl : clusterManager.getPeerUrls()) {
            peerUrl = peerUrl.trim();
            if (peerUrl.isEmpty()) continue;

            String method = op.getType() == WriteOperation.Type.PUT ? "PUT" : "DELETE";
            boolean ok = peerClient.replicateWrite(peerUrl, method, op.getKey(), op.getValue());

            if (ok) {
                log.info("Replicated {} to {}", op, peerUrl);
            } else {
                log.warn("Replication to {} failed for {} — peer may be down", peerUrl, op);
            }
        }
    }
}