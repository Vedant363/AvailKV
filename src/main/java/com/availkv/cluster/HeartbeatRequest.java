package com.availkv.cluster;

public class HeartbeatRequest {
    private String leaderId;
    private int term;

    public HeartbeatRequest() {}

    public HeartbeatRequest(String leaderId, int term) {
        this.leaderId = leaderId;
        this.term = term;
    }

    public String getLeaderId() { return leaderId; }
    public void setLeaderId(String leaderId) { this.leaderId = leaderId; }
    public int getTerm() { return term; }
    public void setTerm(int term) { this.term = term; }
}