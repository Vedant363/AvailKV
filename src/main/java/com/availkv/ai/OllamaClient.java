package com.availkv.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    @Value("${ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${ollama.model:gemma2:2b}")
    private String model;

    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public OllamaClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)   // LLMs can be slow
                .build();
    }

    public String ask(String prompt) {
        try {
            // stream:true tells Ollama to send tokens as they're generated
            String requestBody = mapper.writeValueAsString(
                    new OllamaRequest(model, prompt, true)
            );

            Request request = new Request.Builder()
                    .url(ollamaUrl + "/api/generate")
                    .post(RequestBody.create(requestBody, JSON))
                    .build();

            log.info("Sending prompt to Ollama ({}) — {} chars", model, prompt.length());

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "Ollama returned error: " + response.code();
                }

                // Read the streaming response line by line
                StringBuilder result = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream())
                );

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;

                    JsonNode chunk = mapper.readTree(line);

                    // Append each token to our result
                    JsonNode responseToken = chunk.get("response");
                    if (responseToken != null) {
                        result.append(responseToken.asText());
                    }

                    // Stop when Ollama says it's done
                    JsonNode done = chunk.get("done");
                    if (done != null && done.asBoolean()) break;
                }

                log.info("Ollama response assembled — {} chars", result.length());
                return result.toString();
            }

        } catch (Exception e) {
            log.error("Ollama call failed: {}", e.getMessage());
            return "Could not reach Ollama: " + e.getMessage()
                    + "\n\nMake sure Ollama is running: ollama serve";
        }
    }

    static class OllamaRequest {
        public final String model;
        public final String prompt;
        public final boolean stream;

        OllamaRequest(String model, String prompt, boolean stream) {
            this.model = model;
            this.prompt = prompt;
            this.stream = stream;
        }
    }
}