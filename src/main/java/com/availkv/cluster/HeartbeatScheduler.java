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
    private static final long HEARTBEAT_INTERVAL_MS = 2000;
    private static final long ELECTION_TIMEOUT_MIN_MS = 5000;
    private static final long ELECTION_TIMEOUT_MAX_MS = 8000;

    private final ClusterManager clusterManager;
    private final PeerClient peerClient;
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
            if (peerUrl.isEmpty()) continue;

            boolean ok = peerClient.sendHeartbeat(peerUrl, heartbeat);
            if (ok) {
                // mark peer as alive when heartbeat succeeds
                clusterManager.markPeerSeen(peerUrl);
            } else {
                log.warn("[LEADER] Heartbeat to {} failed — peer may be down", peerUrl);
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
        int totalNodes = peers.length + 1;
        int votes = 1; // self-vote already recorded in startElection()

        VoteRequest voteRequest = new VoteRequest(clusterManager.getNodeId(), term);

        for (String peerUrl : peers) {
            peerUrl = peerUrl.trim();
            if (peerUrl.isEmpty()) continue;

            VoteResponse response = peerClient.requestVote(peerUrl, voteRequest);

            if (response != null) {
                // peer responded — mark it as seen regardless of vote result
                clusterManager.markPeerSeen(peerUrl);

                if (response.isVoteGranted()) {
                    votes++;
                    // record which peer voted
                    clusterManager.recordVote(term, peerUrl, clusterManager.getNodeId());
                    log.info("[{}] Got vote from {} (total: {}/{})",
                            clusterManager.getNodeId(), peerUrl, votes, totalNodes);
                }

                if (response.getTerm() > term) {
                    log.info("[{}] Saw higher term {} during election — stepping down",
                            clusterManager.getNodeId(), response.getTerm());
                    clusterManager.becomeFollower(response.getTerm(), null);
                    return;
                }
            }
            // null response = peer unreachable, peerLastSeen not updated = correctly shows as down
        }

        if (votes > totalNodes / 2) {
            clusterManager.becomeLeader();
        } else {
            log.info("[{}] Lost election ({}/{} votes) — reverting to FOLLOWER",
                    clusterManager.getNodeId(), votes, totalNodes);
            clusterManager.becomeFollower(term, null);
        }
    }
}