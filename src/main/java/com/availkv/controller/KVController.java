package com.availkv.controller;

import com.availkv.cluster.ClusterManager;
import com.availkv.cluster.NodeState;
import com.availkv.storage.KVStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * KVController — updated for Phase 2.
 *
 * Key change from Phase 1:
 * PUT and DELETE now check if this node is the LEADER before accepting writes.
 * Followers return 403 with a hint about who the current leader is.
 *
 * GETs are still allowed on any node — reads don't need quorum for now.
 * (Phase 3 will add the ?replicated=true guard for replication fan-out.)
 */
@RestController
@RequestMapping("/kv")
public class KVController {

    private final KVStore kvStore;
    private final ClusterManager clusterManager;

    public KVController(KVStore kvStore, ClusterManager clusterManager) {
        this.kvStore = kvStore;
        this.clusterManager = clusterManager;
    }

    /**
     * GET /kv/{key}
     * Reads are allowed on any node.
     */
    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        return kvStore.get(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /kv/{key}
     * Only the LEADER accepts writes.
     * ?replicated=true bypasses the leader check (write came from leader replication).
     */
    @PutMapping("/{key}")
    public ResponseEntity<String> put(
            @PathVariable String key,
            @RequestBody String value,
            @RequestParam(defaultValue = "false") boolean replicated) {

        if (!replicated && !clusterManager.isLeader()) {
            return leaderOnlyResponse();
        }

        kvStore.put(key, value);
        return ResponseEntity.ok("OK");
    }

    /**
     * DELETE /kv/{key}
     * Only the LEADER accepts deletes.
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<String> delete(
            @PathVariable String key,
            @RequestParam(defaultValue = "false") boolean replicated) {

        if (!replicated && !clusterManager.isLeader()) {
            return leaderOnlyResponse();
        }

        boolean deleted = kvStore.delete(key);
        return deleted
                ? ResponseEntity.ok("DELETED")
                : ResponseEntity.notFound().build();
    }

    /**
     * Returns 403 with a hint telling the client which node is the current leader.
     * This lets clients redirect themselves to the right node.
     */
    private ResponseEntity<String> leaderOnlyResponse() {
        String leader = clusterManager.getLeaderId();
        String message = leader != null
                ? "Not the leader. Current leader: " + leader
                : "Not the leader. No leader elected yet.";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(message);
    }
}