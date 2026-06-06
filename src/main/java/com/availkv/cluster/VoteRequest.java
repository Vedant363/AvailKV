package com.availkv.cluster;

public class VoteRequest {
    private String candidateId;
    private int term;

    public VoteRequest() {}

    public VoteRequest(String candidateId, int term) {
        this.candidateId = candidateId;
        this.term = term;
    }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }
    public int getTerm() { return term; }
    public void setTerm(int term) { this.term = term; }
}