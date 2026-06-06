package com.availkv.controller;

import com.availkv.cluster.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ClusterController {

    private final ClusterManager clusterManager;

    public ClusterController(ClusterManager clusterManager) {
        this.clusterManager = clusterManager;
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
}