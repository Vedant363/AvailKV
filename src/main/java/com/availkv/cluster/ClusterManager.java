package com.availkv.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    // Max events to keep in memory — older ones are dropped
    private static final int MAX_EVENTS = 50;

    @Value("${node.id}")
    private String nodeId;

    @Value("${peer.urls}")
    private String peerUrlsRaw;

    private final AtomicInteger currentTerm = new AtomicInteger(0);
    private volatile NodeState state = NodeState.FOLLOWER;
    private volatile String leaderId = null;
    private volatile String votedFor = null;
    private volatile long lastHeartbeatTimestamp = System.currentTimeMillis();

    private final List<ClusterEvent> eventLog = Collections.synchronizedList(new ArrayList<>());

    public String getNodeId()                    { return nodeId; }
    public String[] getPeerUrls()                { return peerUrlsRaw.split(","); }
    public int getCurrentTerm()                  { return currentTerm.get(); }
    public NodeState getState()                  { return state; }
    public String getLeaderId()                  { return leaderId; }
    public boolean isLeader()                    { return state == NodeState.LEADER; }
    public long getLastHeartbeatTimestamp()      { return lastHeartbeatTimestamp; }

    public List<ClusterEvent> getRecentEvents(int n) {
        synchronized (eventLog) {
            int size = eventLog.size();
            int from = Math.max(0, size - n);
            return new ArrayList<>(eventLog.subList(from, size));
        }
    }

    public synchronized void becomeLeader() {
        log.info("[{}] Became LEADER for term {}", nodeId, currentTerm.get());
        state = NodeState.LEADER;
        leaderId = nodeId;
        recordEvent("LEADER_ELECTED",
                nodeId + " won election and became LEADER for term " + currentTerm.get());
    }

    public synchronized void becomeFollower(int term, String newLeaderId) {
        if (term > currentTerm.get()) {
            log.info("[{}] Stepping down — saw higher term {} from {}", nodeId, term, newLeaderId);
            recordEvent("STEP_DOWN",
                    nodeId + " stepped down from " + state + " — saw higher term " + term
                            + " from " + (newLeaderId != null ? newLeaderId : "unknown"));
            currentTerm.set(term);
            votedFor = null;
        }
        state = NodeState.FOLLOWER;
        leaderId = newLeaderId;
        lastHeartbeatTimestamp = System.currentTimeMillis();
    }

    public synchronized int startElection() {
        state = NodeState.CANDIDATE;
        int newTerm = currentTerm.incrementAndGet();
        votedFor = nodeId;
        leaderId = null;
        log.info("[{}] Starting election for term {}", nodeId, newTerm);
        recordEvent("ELECTION_STARTED",
                nodeId + " started election for term " + newTerm
                        + " — no heartbeat received within timeout");
        return newTerm;
    }

    public synchronized VoteResponse grantVote(VoteRequest request) {
        int candidateTerm = request.getTerm();
        String candidateId = request.getCandidateId();

        if (candidateTerm < currentTerm.get()) {
            recordEvent("VOTE_REJECTED",
                    nodeId + " rejected vote for " + candidateId
                            + " — stale term " + candidateTerm + " (our term: " + currentTerm.get() + ")");
            return new VoteResponse(false, currentTerm.get());
        }

        if (candidateTerm > currentTerm.get()) {
            currentTerm.set(candidateTerm);
            votedFor = null;
            state = NodeState.FOLLOWER;
        }

        boolean canVote = (votedFor == null || votedFor.equals(candidateId));
        if (canVote) {
            votedFor = candidateId;
            lastHeartbeatTimestamp = System.currentTimeMillis();
            recordEvent("VOTE_GRANTED",
                    nodeId + " granted vote to " + candidateId + " for term " + candidateTerm);
            return new VoteResponse(true, currentTerm.get());
        }

        recordEvent("VOTE_REJECTED",
                nodeId + " rejected vote for " + candidateId
                        + " — already voted for " + votedFor + " in term " + candidateTerm);
        return new VoteResponse(false, currentTerm.get());
    }

    public synchronized boolean processHeartbeat(HeartbeatRequest request) {
        if (request.getTerm() < currentTerm.get()) {
            recordEvent("HEARTBEAT_REJECTED",
                    "Stale heartbeat from " + request.getLeaderId()
                            + " (their term " + request.getTerm()
                            + " < our term " + currentTerm.get() + ") — ignored");
            return false;
        }
        becomeFollower(request.getTerm(), request.getLeaderId());
        return true;
    }

    private void recordEvent(String type, String description) {
        ClusterEvent event = new ClusterEvent(type, description, Instant.now());
        synchronized (eventLog) {
            eventLog.add(event);
            // Keep the list bounded — drop oldest when full
            if (eventLog.size() > MAX_EVENTS) {
                eventLog.remove(0);
            }
        }
    }
}