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

        String context = clusterContext.build();
        String prompt = buildPrompt(context, question.trim());
        String answer = ollamaClient.ask(prompt);

        return ResponseEntity.ok(answer);
    }

    private String buildPrompt(String context, String question) {
        return """
                You are a diagnostic assistant for AvailKV, a distributed key-value store.
                
                STRICT RULES — you must follow all of these:
                1. Answer ONLY using facts present in the CLUSTER STATE section below.
                2. Do NOT give generic distributed systems advice.
                3. Do NOT speculate about things not mentioned in the context.
                4. Every claim you make must reference a specific field or event from the context.
                5. If the context does not contain enough information to answer the question,
                   respond with exactly: "The cluster context does not contain enough information to answer this. Observed facts: [list what you do see]"
                6. Keep answers short — 3 to 6 sentences max.
                7. Do not use bullet points. Answer in plain sentences.
                8. Do not suggest troubleshooting steps unless a ⚠ FACT warning appears in the context.
                
                CLUSTER STATE:
                """ + context + """
                
                QUESTION: """ + question + """
                
                ANSWER (facts from context only, 3-6 sentences, no bullet points):
                """;
    }
}