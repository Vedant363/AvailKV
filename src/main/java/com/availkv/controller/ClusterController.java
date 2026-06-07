package com.availkv.controller;

import com.availkv.cluster.*;
import com.availkv.replication.WriteOperation;
import com.availkv.storage.WALManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class ClusterController {

    private final ClusterManager clusterManager;
    private final WALManager walManager;

    public ClusterController(ClusterManager clusterManager, WALManager walManager) {
        this.clusterManager = clusterManager;
        this.walManager = walManager;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<String> receiveHeartbeat(@RequestBody HeartbeatRequest request) {
        boolean accepted = clusterManager.processHeartbeat(request);
        return accepted
                ? ResponseEntity.ok("OK")
                : ResponseEntity.badRequest().body("STALE_TERM");
    }

    @PostMapping("/vote")
    public ResponseEntity<VoteResponse> vote(@RequestBody VoteRequest request) {
        VoteResponse response = clusterManager.grantVote(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        String status = String.format("%s | %s | term=%d | leader=%s",
                clusterManager.getNodeId(),
                clusterManager.getState(),
                clusterManager.getCurrentTerm(),
                clusterManager.getLeaderId());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/wal")
    public ResponseEntity<String> getWal() {
        List<WriteOperation> ops = walManager.readAll();
        if (ops.isEmpty()) {
            return ResponseEntity.ok("(no writes yet)");
        }
        String content = ops.stream()
                .map(WriteOperation::toString)
                .collect(Collectors.joining("\n"));
        return ResponseEntity.ok(content);
    }
}