package com.availkv.cluster;

public class VoteRequest {
    private String candidateId;
    private int term;
    private int logSize;

    public VoteRequest() {}

    public VoteRequest(String candidateId, int term, int logSize) {
        this.candidateId = candidateId;
        this.term = term;
        this.logSize = logSize;
    }

    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String candidateId) { this.candidateId = candidateId; }
    public int getTerm() { return term; }
    public void setTerm(int term) { this.term = term; }
    public int getLogSize() { return logSize; }
    public void setLogSize(int logSize) { this.logSize = logSize; }
}