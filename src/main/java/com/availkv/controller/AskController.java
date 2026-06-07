package com.availkv.controller;

import com.availkv.ai.ClusterContext;
import com.availkv.ai.OllamaClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AskController {

    private final ClusterContext clusterContext;
    private final OllamaClient ollamaClient;

    public AskController(ClusterContext clusterContext, OllamaClient ollamaClient) {
        this.clusterContext = clusterContext;
        this.ollamaClient = ollamaClient;
    }

    @PostMapping("/ask")
    public ResponseEntity<String> ask(@RequestBody String question) {
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body("Question cannot be empty.");
        }

        // Build the live cluster snapshot
        String context = clusterContext.build();

        // The structure matters: system role → context → question
        String prompt = buildPrompt(context, question.trim());

        // Send to Ollama and get the answer
        String answer = ollamaClient.ask(prompt);

        return ResponseEntity.ok(answer);
    }

    private String buildPrompt(String context, String question) {
        return """
                SYSTEM:
                You are an expert distributed systems engineer and operational assistant
                for AvailKV, a distributed in-memory key-value store using Raft-style
                leader election and write-ahead logging for durability.
                
                You have access to a live snapshot of the cluster's current state below.
                Use it to give specific, accurate answers. If the context doesn't contain
                enough information to answer confidently, say so clearly.
                Keep answers concise and actionable.
                
                CLUSTER STATE:
                """ + context + """
                
                QUESTION:
                """ + question + """
                
                ANSWER:
                """;
    }
}