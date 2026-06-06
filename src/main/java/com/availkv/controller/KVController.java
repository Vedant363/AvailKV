package com.availkv.controller;

import com.availkv.cluster.ClusterManager;
import com.availkv.cluster.NodeState;
import com.availkv.storage.KVStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kv")
public class KVController {

    private final KVStore kvStore;
    private final ClusterManager clusterManager;

    public KVController(KVStore kvStore, ClusterManager clusterManager) {
        this.kvStore = kvStore;
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

        kvStore.put(key, value);
        return ResponseEntity.ok("OK");
    }

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

    private ResponseEntity<String> leaderOnlyResponse() {
        String leader = clusterManager.getLeaderId();
        String message = leader != null
                ? "Not the leader. Current leader: " + leader
                : "Not the leader. No leader elected yet.";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(message);
    }
}