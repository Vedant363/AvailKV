package com.availkv.cluster;

import com.availkv.storage.WALManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ClusterManager {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);
    private static final int MAX_EVENTS = 50;

    @Value("${node.id}")
    private String nodeId;

    @Value("${peer.urls}")
    private String peerUrlsRaw;

    @Value("${peer.ids:}")
    private String peerIdsRaw;

    private final AtomicInteger currentTerm = new AtomicInteger(0);
    private volatile NodeState state = NodeState.FOLLOWER;
    private volatile String leaderId = null;
    private volatile String votedFor = null;
    private volatile long lastHeartbeatTimestamp = System.currentTimeMillis();
    private final WALManager walManager;
    public ClusterManager(WALManager walManager) {
        this.walManager = walManager;
    }

    private final List<ClusterEvent> eventLog = Collections.synchronizedList(new ArrayList<>());

    private final Map<Integer, Map<String, String>> voteLog = new ConcurrentHashMap<>();

    private final Map<String, Long> peerLastSeen = new ConcurrentHashMap<>();

    public String getNodeId()               { return nodeId; }
    public String[] getPeerUrls()           { return peerUrlsRaw.split(","); }
    public int getCurrentTerm()             { return currentTerm.get(); }
    public NodeState getState()             { return state; }
    public String getLeaderId()             { return leaderId; }
    public boolean isLeader()               { return state == NodeState.LEADER; }
    public long getLastHeartbeatTimestamp() { return lastHeartbeatTimestamp; }

    public List<ClusterEvent> getRecentEvents(int n) {
        synchronized (eventLog) {
            int size = eventLog.size();
            return new ArrayList<>(eventLog.subList(Math.max(0, size - n), size));
        }
    }

    public Map<String, String> getVotesForTerm(int term) {
        return voteLog.getOrDefault(term, Collections.emptyMap());
    }

    public Map<Integer, Map<String, String>> getVoteLog() {
        return Collections.unmodifiableMap(voteLog);
    }

    public Map<String, Long> getPeerLastSeen() {
        return Collections.unmodifiableMap(peerLastSeen);
    }

    public void markPeerSeen(String peerUrl) {
        peerLastSeen.put(peerUrl.trim(), System.currentTimeMillis());
    }

    public Map<String, String> getPeerUrlToName() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        String[] urls = peerUrlsRaw.split(",");
        String[] ids  = (peerIdsRaw == null || peerIdsRaw.isBlank()) ? new String[0] : peerIdsRaw.split(",");
        for (int i = 0; i < urls.length; i++) {
            String url  = urls[i].trim();
            String name = (i < ids.length) ? ids[i].trim() : url;
            map.put(url, name);
        }
        return map;
    }

    public void recordVote(int term, String voterId, String candidate) {
        voteLog.computeIfAbsent(term, k -> new ConcurrentHashMap<>())
                .put(voterId, candidate);
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
                    nodeId + " stepped down from " + state
                            + " — saw higher term " + term
                            + " from " + (newLeaderId != null ? newLeaderId : "unknown"));
            currentTerm.set(term);
            votedFor = null;
        }
        state = NodeState.FOLLOWER;
        leaderId = newLeaderId;
        lastHeartbeatTimestamp = System.currentTimeMillis();
        if (newLeaderId != null) {
            // The node we heard from is clearly alive
            for (String url : getPeerUrls()) {
                if (url.contains(newLeaderId)) markPeerSeen(url);
            }
        }
    }

    public synchronized int startElection() {
        state = NodeState.CANDIDATE;
        int newTerm = currentTerm.incrementAndGet();
        votedFor = nodeId;
        leaderId = null;
        // Record self-vote
        recordVote(newTerm, nodeId, nodeId);
        log.info("[{}] Starting election for term {}", nodeId, newTerm);
        recordEvent("ELECTION_STARTED",
                nodeId + " started election for term " + newTerm
                        + " — no heartbeat received within timeout");
        return newTerm;
    }

    public synchronized VoteResponse grantVote(VoteRequest request) {
        int candidateTerm = request.getTerm();
        String candidateId = request.getCandidateId();
        int candidateLogSize = request.getLogSize();

        // Reject if candidate's term is stale
        if (candidateTerm < currentTerm.get()) {
            recordEvent("VOTE_REJECTED",
                    nodeId + " rejected vote for " + candidateId
                            + " — stale term " + candidateTerm
                            + " (our term: " + currentTerm.get() + ")");
            return new VoteResponse(false, currentTerm.get());
        }

        // Candidate has higher term — update ours and reset vote
        if (candidateTerm > currentTerm.get()) {
            currentTerm.set(candidateTerm);
            votedFor = null;
            state = NodeState.FOLLOWER;
        }

        // ── Log freshness check (Raft election restriction) ──────────────
        // Reject if our log is more complete than the candidate's.
        // This prevents a stale node from becoming leader and serving
        // deleted or overwritten data as the new source of truth.
        int myLogSize = walManager.readAll().size();
        if (candidateLogSize < myLogSize) {
            recordEvent("VOTE_REJECTED",
                    nodeId + " rejected vote for " + candidateId
                            + " — candidate log size " + candidateLogSize
                            + " < our log size " + myLogSize);
            return new VoteResponse(false, currentTerm.get());
        }

        // Grant vote if we haven't voted yet this term
        boolean canVote = (votedFor == null || votedFor.equals(candidateId));
        if (canVote) {
            votedFor = candidateId;
            lastHeartbeatTimestamp = System.currentTimeMillis();
            recordVote(candidateTerm, nodeId, candidateId);
            recordEvent("VOTE_GRANTED",
                    nodeId + " voted for " + candidateId
                            + " in term " + candidateTerm
                            + " (candidate log: " + candidateLogSize
                            + ", our log: " + myLogSize + ")");
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
        // Mark leader as seen
        for (String url : getPeerUrls()) {
            if (url.contains(request.getLeaderId())) markPeerSeen(url);
        }
        becomeFollower(request.getTerm(), request.getLeaderId());
        return true;
    }

    private void recordEvent(String type, String description) {
        ClusterEvent event = new ClusterEvent(type, description, Instant.now());
        synchronized (eventLog) {
            eventLog.add(event);
            if (eventLog.size() > MAX_EVENTS) eventLog.remove(0);
        }
    }
}