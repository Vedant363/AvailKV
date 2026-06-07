package com.availkv.controller;

import com.availkv.cluster.ClusterManager;
import com.availkv.replication.ReplicationService;
import com.availkv.storage.KVStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kv")
public class KVController {

    private final KVStore kvStore;
    private final ReplicationService replicationService;
    private final ClusterManager clusterManager;

    public KVController(KVStore kvStore,
                        ReplicationService replicationService,
                        ClusterManager clusterManager) {
        this.kvStore = kvStore;
        this.replicationService = replicationService;
        this.clusterManager = clusterManager;
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        return kvStore.get(key)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{key}")
    public ResponseEntity<String> put(
            @PathVariable String key,
            @RequestBody String value,
            @RequestParam(defaultValue = "false") boolean replicated) {

        if (!replicated && !clusterManager.isLeader()) {
            return leaderOnlyResponse();
        }

        replicationService.put(key, value, replicated);
        return ResponseEntity.ok("OK");
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<String> delete(
            @PathVariable String key,
            @RequestParam(defaultValue = "false") boolean replicated) {

        if (!replicated && !clusterManager.isLeader()) {
            return leaderOnlyResponse();
        }

        // Check existence before going through replication pipeline
        if (kvStore.get(key).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        replicationService.delete(key, replicated);
        return ResponseEntity.ok("DELETED");
    }

    private ResponseEntity<String> leaderOnlyResponse() {
        String leader = clusterManager.getLeaderId();
        String message = leader != null
                ? "Not the leader. Current leader: " + leader
                : "Not the leader. No leader elected yet.";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(message);
    }
}