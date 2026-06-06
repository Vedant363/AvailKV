package com.availkv.client;

import com.availkv.cluster.VoteRequest;
import com.availkv.cluster.VoteResponse;
import com.availkv.cluster.HeartbeatRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public PeerClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
    }

    public boolean sendHeartbeat(String peerUrl, HeartbeatRequest request) {
        try {
            String json = mapper.writeValueAsString(request);
            Request httpRequest = new Request.Builder()
                    .url(peerUrl + "/heartbeat")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = http.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            // Peer is down or unreachable — log and return false
            log.warn("Heartbeat to {} failed: {}", peerUrl, e.getMessage());
            return false;
        }
    }

    public VoteResponse requestVote(String peerUrl, VoteRequest request) {
        try {
            String json = mapper.writeValueAsString(request);
            Request httpRequest = new Request.Builder()
                    .url(peerUrl + "/vote")
                    .post(RequestBody.create(json, JSON))
                    .build();

            try (Response response = http.newCall(httpRequest).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return mapper.readValue(response.body().string(), VoteResponse.class);
                }
            }
        } catch (Exception e) {
            log.warn("Vote request to {} failed: {}", peerUrl, e.getMessage());
        }
        return null;    // null = no vote received
    }

    public boolean replicateWrite(String peerUrl, String method, String key, String value) {
        try {
            String url = peerUrl + "/kv/" + key + "?replicated=true";
            RequestBody body = value != null
                    ? RequestBody.create(value, MediaType.get("text/plain"))
                    : RequestBody.create("", MediaType.get("text/plain"));

            Request httpRequest = new Request.Builder()
                    .url(url)
                    .method(method, "DELETE".equals(method) ? null : body)
                    .build();

            try (Response response = http.newCall(httpRequest).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("Replication to {} failed: {}", peerUrl, e.getMessage());
            return false;
        }
    }
}