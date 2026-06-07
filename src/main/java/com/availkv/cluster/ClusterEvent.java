package com.availkv.cluster;

import java.time.Instant;

public class ClusterEvent {

    private final String type;          // e.g. ELECTION_STARTED, LEADER_ELECTED
    private final String description;   // human-readable detail
    private final Instant timestamp;

    public ClusterEvent(String type, String description, Instant timestamp) {
        this.type = type;
        this.description = description;
        this.timestamp = timestamp;
    }

    public String getType()           { return type; }
    public String getDescription()    { return description; }
    public Instant getTimestamp()     { return timestamp; }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + type + ": " + description;
    }
}