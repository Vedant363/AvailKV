package com.availkv.replication;

public class QuorumNotAvailableException extends RuntimeException {
    public QuorumNotAvailableException(String message) {
        super(message);
    }
}