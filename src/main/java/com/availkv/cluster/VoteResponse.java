package com.availkv.cluster;

public class VoteResponse {
    private boolean voteGranted;
    private int term;

    public VoteResponse() {}

    public VoteResponse(boolean voteGranted, int term) {
        this.voteGranted = voteGranted;
        this.term = term;
    }

    public boolean isVoteGranted() { return voteGranted; }
    public void setVoteGranted(boolean voteGranted) { this.voteGranted = voteGranted; }
    public int getTerm() { return term; }
    public void setTerm(int term) { this.term = term; }
}