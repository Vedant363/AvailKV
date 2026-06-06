package com.availkv.cluster;

import com.availkv.client.PeerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    // Leader sends heartbeat every 2000ms
    private static final long HEARTBEAT_INTERVAL_MS = 2000;

    // Followers wait a random 5000–8000ms before triggering election
    private static final long ELECTION_TIMEOUT_MIN_MS = 5000;
    private static final long ELECTION_TIMEOUT_MAX_MS = 8000;

    private final ClusterManager clusterManager;
    private final PeerClient peerClient;

    // Each node picks a random timeout once at startup and keeps it.
    // This is intentional — same node always waits the same duration
    // which makes debugging predictable.
    private final long electionTimeoutMs;

    public HeartbeatScheduler(ClusterManager clusterManager, PeerClient peerClient) {
        this.clusterManager = clusterManager;
        this.peerClient = peerClient;
        this.electionTimeoutMs = ThreadLocalRandom.current()
                .nextLong(ELECTION_TIMEOUT_MIN_MS, ELECTION_TIMEOUT_MAX_MS);
        log.info("Election timeout set to {}ms", electionTimeoutMs);
    }

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void sendHeartbeats() {
        if (!clusterManager.isLeader()) return;

        HeartbeatRequest heartbeat = new HeartbeatRequest(
                clusterManager.getNodeId(),
                clusterManager.getCurrentTerm()
        );

        for (String peerUrl : clusterManager.getPeerUrls()) {
            peerUrl = peerUrl.trim();
            if (!peerUrl.isEmpty()) {
                boolean ok = peerClient.sendHeartbeat(peerUrl, heartbeat);
                if (!ok) {
                    log.warn("[LEADER] Heartbeat to {} failed — peer may be down", peerUrl);
                }
            }
        }
    }

    @Scheduled(fixedRate = 1000)
    public void checkHeartbeatTimeout() {
        if (clusterManager.isLeader()) return;

        long elapsed = System.currentTimeMillis() - clusterManager.getLastHeartbeatTimestamp();

        if (elapsed > electionTimeoutMs) {
            log.info("[{}] Heartbeat timeout after {}ms — starting election",
                    clusterManager.getNodeId(), elapsed);
            runElection();
        }
    }

    private void runElection() {
        int term = clusterManager.startElection();

        String[] peers = clusterManager.getPeerUrls();
        int totalNodes = peers.length + 1;  // +1 for self
        int votes = 1;                       // self-vote

        com.availkv.cluster.VoteRequest voteRequest = new com.availkv.cluster.VoteRequest(
                clusterManager.getNodeId(), term
        );

        for (String peerUrl : peers) {
            peerUrl = peerUrl.trim();
            if (peerUrl.isEmpty()) continue;

            com.availkv.cluster.VoteResponse response = peerClient.requestVote(peerUrl, voteRequest);

            if (response != null && response.isVoteGranted()) {
                votes++;
                log.info("[{}] Got vote from {} (total: {}/{})",
                        clusterManager.getNodeId(), peerUrl, votes, totalNodes);
            }

            // If a peer reports a higher term, we're stale — step down immediately
            if (response != null && response.getTerm() > term) {
                log.info("[{}] Saw higher term {} during election — stepping down",
                        clusterManager.getNodeId(), response.getTerm());
                clusterManager.becomeFollower(response.getTerm(), null);
                return;
            }
        }

        // Majority check: strictly greater than half
        if (votes > totalNodes / 2) {
            clusterManager.becomeLeader();
        } else {
            log.info("[{}] Lost election (got {}/{} votes) — reverting to FOLLOWER",
                    clusterManager.getNodeId(), votes, totalNodes);
            clusterManager.becomeFollower(term, null);
        }
    }
}