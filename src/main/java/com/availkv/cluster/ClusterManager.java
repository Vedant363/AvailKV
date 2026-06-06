package com.availkv.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    @Value("${node.id}")
    private String nodeId;

    @Value("${peer.urls}")
    private String peerUrlsRaw;

    // ── Cluster state ────────────────────────────────────────────────────

    private final AtomicInteger currentTerm = new AtomicInteger(0);

    private volatile NodeState state = NodeState.FOLLOWER;

    private volatile String leaderId = null;

    private volatile String votedFor = null;

    private volatile long lastHeartbeatTimestamp = System.currentTimeMillis();

    // ── Accessors ────────────────────────────────────────────────────────

    public String getNodeId() { return nodeId; }

    public String[] getPeerUrls() {
        return peerUrlsRaw.split(",");
    }

    public int getCurrentTerm() { return currentTerm.get(); }

    public NodeState getState() { return state; }

    public String getLeaderId() { return leaderId; }

    public boolean isLeader() { return state == NodeState.LEADER; }

    public long getLastHeartbeatTimestamp() { return lastHeartbeatTimestamp; }

    // ── State transitions ────────────────────────────────────────────────

    public synchronized void becomeLeader() {
        log.info("[{}] Became LEADER for term {}", nodeId, currentTerm.get());
        state = NodeState.LEADER;
        leaderId = nodeId;
    }

    public synchronized void becomeFollower(int term, String newLeaderId) {
        if (term > currentTerm.get()) {
            log.info("[{}] Stepping down — saw higher term {} from {}", nodeId, term, newLeaderId);
            currentTerm.set(term);
            votedFor = null;    // reset vote for the new term
        }
        state = NodeState.FOLLOWER;
        leaderId = newLeaderId;
        lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    public synchronized int startElection() {
        state = NodeState.CANDIDATE;
        int newTerm = currentTerm.incrementAndGet();
        votedFor = nodeId;      // vote for ourselves
        leaderId = null;        // we don't know who the leader is yet
        log.info("[{}] Starting election for term {}", nodeId, newTerm);
        return newTerm;
    }

    public synchronized VoteResponse grantVote(VoteRequest request) {
        int candidateTerm = request.getTerm();
        String candidateId = request.getCandidateId();

        // Candidate is behind — reject
        if (candidateTerm < currentTerm.get()) {
            log.info("[{}] Rejecting vote for {} — stale term {}", nodeId, candidateId, candidateTerm);
            return new VoteResponse(false, currentTerm.get());
        }

        // Candidate is ahead — update our term and reset our vote
        if (candidateTerm > currentTerm.get()) {
            currentTerm.set(candidateTerm);
            votedFor = null;
            state = NodeState.FOLLOWER;
        }

        // Grant vote if we haven't voted yet this term
        boolean canVote = (votedFor == null || votedFor.equals(candidateId));
        if (canVote) {
            votedFor = candidateId;
            lastHeartbeatTimestamp = System.currentTimeMillis(); // treat vote as activity
            log.info("[{}] Voted for {} in term {}", nodeId, candidateId, candidateTerm);
            return new VoteResponse(true, currentTerm.get());
        }

        log.info("[{}] Rejecting vote for {} — already voted for {}", nodeId, candidateId, votedFor);
        return new VoteResponse(false, currentTerm.get());
    }

    public synchronized boolean processHeartbeat(HeartbeatRequest request) {
        if (request.getTerm() < currentTerm.get()) {
            // This heartbeat is from an old leader — reject it
            log.warn("[{}] Ignoring stale heartbeat from {} (term {})", nodeId, request.getLeaderId(), request.getTerm());
            return false;
        }
        becomeFollower(request.getTerm(), request.getLeaderId());
        return true;
    }
}