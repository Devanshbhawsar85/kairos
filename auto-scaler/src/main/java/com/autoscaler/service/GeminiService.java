package com.autoscaler.service;

import com.autoscaler.model.ContainerStats;
import com.autoscaler.model.ScalingDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls Groq AI to decide the scaling action and generate a fix plan.
 *
 * RAG PATTERN (pgvector edition):
 *   Before building the prompt, VectorStoreService performs cosine-similarity
 *   search in pgvector to retrieve the most contextually relevant historical
 *   data — not just the most-recent N rows.
 *
 *   getSimilarSnapshots → top-K metric readings most similar to current state
 *   getSimilarEvents    → top-K scaling decisions most similar to current state
 *
 * Firebase is completely removed.
 *
 * Response JSON schema expected from Groq:
 * {
 *   "action":   "SCALE_UP",
 *   "reason":   "CPU trending 40→65→85%",
 *   "severity": "HIGH",
 *   "summary":  "One-line human summary",
 *   "fixPlan":  ["Step 1", "Step 2", "Step 3"]
 * }
 */
@Slf4j
@Service
public class GeminiService {

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${autoscaler.vector-store.top-k-snapshots:20}")
    private int topKSnapshots;

    @Value("${autoscaler.vector-store.top-k-events:10}")
    private int topKEvents;

    private final WebClient         webClient;
    private final VectorStoreService vectorStore;
    private final ObjectMapper      objectMapper;

    // Max chars of pgvector history to embed — keeps Groq token cost bounded
    private static final int MAX_SNAPSHOT_CHARS = 1500;
    private static final int MAX_EVENT_CHARS    =  800;

    public GeminiService(WebClient webClient, VectorStoreService vectorStore,
                         ObjectMapper objectMapper) {
        this.webClient    = webClient;
        this.vectorStore  = vectorStore;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Fetches semantically-relevant RAG context from pgvector, builds the
     * Groq prompt, and returns a parsed ScalingDecision.
     *
     * Returns null if the API key is absent or the call fails —
     * AutoScalerService falls back to rule-based decisions.
     */
    public ScalingDecision analyzeAndPlan(ContainerStats stats) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No Groq API key — skipping AI analysis");
            return null;
        }

        try {
            // ── STEP 1: Build semantic query from current metrics ────
            String semanticQuery = buildSemanticQuery(stats);

            // ── STEP 2: Fetch similar historical context from pgvector
            String similarSnapshots = vectorStore.getSimilarSnapshots(
                    stats.getContainerName(), semanticQuery, topKSnapshots);
            String similarEvents = vectorStore.getSimilarEvents(
                    stats.getContainerName(), semanticQuery, topKEvents);

            log.debug("RAG context for {} — snapshots:{} chars, events:{} chars",
                    stats.getContainerName(),
                    similarSnapshots.length(),
                    similarEvents.length());

            // ── STEP 3: Build prompt ──────────────────────────────────
            String prompt = buildPrompt(stats, similarSnapshots, similarEvents);

            // ── STEP 4: Call Groq ──────────────────────────────────────
            String rawReply = callGroq(prompt);
            if (rawReply == null) return null;

            log.info("Groq AI responded successfully for {}", stats.getContainerName());
            return parseResponse(rawReply);

        } catch (Exception e) {
            log.error("Groq analysis failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Semantic query builder
    // ─────────────────────────────────────────────────────────────

    private String buildSemanticQuery(ContainerStats stats) {
        return String.format(
                "container %s status %s CPU %.1f%% memory %.1f%% netRx %dKB netTx %dKB",
                stats.getContainerName(),
                stats.getStatus(),
                stats.getCpuPercent(),
                stats.getMemoryPercent(),
                stats.getNetworkRxBytes() / 1024,
                stats.getNetworkTxBytes() / 1024
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Prompt construction
    // ─────────────────────────────────────────────────────────────

    private String buildPrompt(ContainerStats stats,
                               String similarSnapshots,
                               String similarEvents) {
        String logs = stats.getRecentLogs();
        if (logs == null) logs = "(no logs available)";
        if (logs.length() > 400) logs = "..." + logs.substring(logs.length() - 400);

        String snapHistory  = trimTail(similarSnapshots, MAX_SNAPSHOT_CHARS);
        String eventHistory = trimTail(similarEvents,    MAX_EVENT_CHARS);

        return "You are a senior DevOps engineer monitoring a Java Spring Boot application running in Docker.\n\n"
                + "## Current container metrics\n"
                + "- Container name : " + stats.getContainerName() + "\n"
                + "- Status         : " + stats.getStatus() + "\n"
                + String.format("- CPU usage      : %.1f%%\n", stats.getCpuPercent())
                + String.format("- Memory usage   : %.1f%% (%d MB / %d MB)\n",
                stats.getMemoryPercent(),
                stats.getMemoryUsed()  / (1024 * 1024),
                stats.getMemoryLimit() / (1024 * 1024))
                + String.format("- Net RX / TX    : %d KB / %d KB\n\n",
                stats.getNetworkRxBytes() / 1024,
                stats.getNetworkTxBytes() / 1024)
                + "## Recent log stream (last ~400 chars)\n"
                + "```\n" + logs + "\n```\n\n"
                + "## Similar historical metric snapshots (pgvector semantic search)\n"
                + "Use this to detect TRENDS and PATTERNS:\n"
                + "- CPU steadily climbing in similar past situations? → proactive SCALE_UP\n"
                + "- Memory growing 2-3% per cycle in similar contexts? → possible memory leak\n"
                + "- Multiple restarts in similar past windows? → crash loop, RESTART\n"
                + "```json\n" + snapHistory + "\n```\n\n"
                + "## Similar historical scaling decisions (pgvector semantic search)\n"
                + "Use this to avoid flip-flopping:\n"
                + "- SCALE_UP published < 3 minutes ago in similar context? → prefer NONE over SCALE_DOWN\n"
                + "- Repeated SCALE_UP decisions not effective? → escalate severity\n"
                + "- SCALE_DOWN happened recently and metrics rose again? → note in reason\n"
                + "```json\n" + eventHistory + "\n```\n\n"
                + "## Your task\n"
                + "Analyse BOTH current metrics AND similar historical patterns.\n"
                + "Respond with a SINGLE valid JSON object (no markdown fences, no extra text):\n"
                + "{\n"
                + "  \"action\":   \"<SCALE_UP | SCALE_DOWN | RESTART | NONE>\",\n"
                + "  \"reason\":   \"<one sentence, reference historical pattern if relevant>\",\n"
                + "  \"severity\": \"<LOW | MEDIUM | HIGH>\",\n"
                + "  \"summary\":  \"<one sentence human-readable summary>\",\n"
                + "  \"fixPlan\":  [\"<Step 1>\", \"<Step 2>\", \"<Step 3>\"]\n"
                + "}\n\n"
                + "## Decision rules\n"
                + "- SCALE_UP   → CPU > 70% OR memory > 80% OR repeated ERROR/Exception in logs\n"
                + "               OR CPU trending up consistently across similar past snapshots\n"
                + "- SCALE_DOWN → CPU < 25% AND memory < 40% AND no recent errors\n"
                + "               AND no SCALE_UP event in last 3 minutes\n"
                + "- RESTART    → status 'exited'/'dead' OR OOMKilled in logs OR crash loop pattern\n"
                + "- NONE       → everything healthy and stable\n\n"
                + "The fixPlan should have 3-5 concrete, actionable steps for the chosen action.";
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP call — Groq OpenAI-compatible API
    // ─────────────────────────────────────────────────────────────

    private String callGroq(String prompt) {
        String url = "https://api.groq.com/openai/v1/chat/completions";

        Map<String, Object> body = Map.of(
                "model",       model,
                "messages",    List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.2,
                "max_tokens",  1200
        );

        try {
            return webClient.post()
                    .uri(url)
                    .header("Content-Type",  "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.error("Groq HTTP call failed: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Response parsing
    // ─────────────────────────────────────────────────────────────

    private ScalingDecision parseResponse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("choices").get(0)
                    .path("message").path("content").asText();

            // Strip any accidental markdown fences
            text = text.replaceAll("```json", "").replaceAll("```", "").trim();

            int start = text.indexOf('{');
            int end   = text.lastIndexOf('}');
            if (start < 0 || end < 0) {
                throw new IllegalStateException("No JSON object found in Groq response");
            }

            JsonNode d = objectMapper.readTree(text.substring(start, end + 1));

            List<String> fixPlan = new ArrayList<>();
            JsonNode planNode = d.path("fixPlan");
            if (planNode.isArray()) planNode.forEach(n -> fixPlan.add(n.asText()));

            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.valueOf(
                            d.path("action").asText("NONE")))
                    .reason(d.path("reason").asText(""))
                    .severity(ScalingDecision.Severity.valueOf(
                            d.path("severity").asText("LOW")))
                    .summary(d.path("summary").asText(""))
                    .fixPlan(fixPlan)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Groq response: {} | raw={}", e.getMessage(), raw);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private String trimTail(String s, int maxChars) {
        if (s == null || s.isBlank()) return "{}";
        return s.length() > maxChars
                ? "...(truncated)..." + s.substring(s.length() - maxChars)
                : s;
    }
}