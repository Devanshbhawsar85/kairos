package com.autoscaler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Converts any text string into a 384-dimensional float[] embedding vector
 * by calling Jina AI's /v1/embeddings endpoint.
 *
 * Model used: jina-embeddings-v2-base-en (384 dims, fast, strong retrieval quality).
 * If no API key is configured, returns a zero vector so the rest of the system
 * degrades gracefully (pgvector will fall back to recency-ordered results).
 *
 * DIMS must match the vector(N) column definition in init.sql — both are 384.
 */
@Slf4j
@Service
public class EmbeddingService {

    /** Must match vector(384) in init.sql */
    public static final int DIMS = 768;

    private static final String EMBED_MODEL = "jina-embeddings-v2-base-en";
    private static final String EMBED_URL   = "https://api.jina.ai/v1/embeddings";

    @Value("${jina.api.key:}")
    private String jinaApiKey;

    private final WebClient    webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingService(WebClient webClient) {
        this.webClient = webClient;
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Embeds {@code text} and returns a float[DIMS] vector.
     * Returns a zero vector (not null) on any failure so callers
     * don't need null checks.
     */
    public float[] embed(String text) {
        if (jinaApiKey == null || jinaApiKey.isBlank()) {
            log.debug("No Jina API key — returning zero embedding");
            return zeroVector();
        }
        if (text == null || text.isBlank()) {
            return zeroVector();
        }

        try {
            String raw = webClient.post()
                    .uri(EMBED_URL)
                    .header("Content-Type",  "application/json")
                    .header("Authorization", "Bearer " + jinaApiKey)
                    .bodyValue(Map.of(
                            "model", EMBED_MODEL,
                            "input", List.of(truncate(text, 4000))
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode arr = mapper.readTree(raw)
                    .path("data").get(0)
                    .path("embedding");

            float[] vec = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vec[i] = (float) arr.get(i).asDouble();
            }
            log.debug("✅ Jina embedding generated: {} dims", vec.length);
            return vec;

        } catch (Exception e) {
            log.error("Jina embedding call failed: {}", e.getMessage());
            return zeroVector();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private float[] zeroVector() {
        return new float[DIMS];
    }

    private String truncate(String s, int maxChars) {
        return s.length() > maxChars ? s.substring(0, maxChars) : s;
    }
}