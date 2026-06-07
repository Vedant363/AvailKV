package com.availkv.replication;

import com.availkv.client.PeerClient;
import com.availkv.cluster.ClusterManager;
import com.availkv.storage.KVStore;
import com.availkv.storage.WALManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

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

    private void execute(WriteOperation op, boolean replicated) {
        log.info("[{}] Executing {} (replicated={})",
                clusterManager.getNodeId(), op, replicated);

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