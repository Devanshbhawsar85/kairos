package com.autoscaler.service;

import com.autoscaler.model.ContainerStats;
import com.autoscaler.model.ScalingDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoScalerService {

    private final DockerService        dockerService;
    private final GeminiService        geminiService;
    private final KafkaProducerService kafkaProducer;
    private final VectorStoreService   vectorStore;
    private final ObjectMapper         objectMapper;
    private final ReplicaStateService  replicaStateService;


    @Value("${autoscaler.scaling.cooldown-seconds:120}")
    private long cooldownSeconds;


    @Value("${autoscaler.docker.container-prefix:main-app}")
    private String containerPrefix;

    @Value("${autoscaler.docker.base-container-name:main-app-1}")
    private String baseContainerName;

    private final Map<String, Long> lastAiCallTime = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────

    public void monitorAndScale() {
        var containers = dockerService.listContainers();

        if (containers.isEmpty()) {
            log.warn("No managed containers found — nothing to monitor");
            return;
        }

        for (var container : containers) {
            String name = extractName(container);
            try {

                boolean isBase = name.equals(baseContainerName);
                monitorContainer(name, isBase);
            } catch (Exception e) {
                log.error("Error monitoring container {}: {}", name, e.getMessage(), e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Per-container cycle
    // ─────────────────────────────────────────────────────────────

    private void monitorContainer(String containerName, boolean makeScalingDecision) {
        log.info("Monitoring container: {}", containerName);

        // ── 1. Collect Docker stats ───────────────────────────────
        ContainerStats stats = dockerService.getContainerStats(containerName);
        if (stats == null) {
            log.warn("Could not retrieve stats for {} — skipping cycle", containerName);
            return;
        }


        persistSnapshot(stats);

        // ── 3. Detect log errors → publish log alert ──────────────
        checkLogsAndAlert(stats);

        // ── 4. Publish metrics update to analytics-worker ────────
        kafkaProducer.publishMetricsUpdate(stats);


        if (!makeScalingDecision) {
            log.debug("Replica {} — metrics collected, skipping scaling decision", containerName);
            return;
        }

        // ── 5. Decide: AI or rule-based ───────────────────────────
        int fromReplicas = replicaStateService.getCurrentReplicas();
        boolean aiUsed   = false;
        ScalingDecision decision;

        long rateLimitWindowMs = cooldownSeconds * 1000L;

        if (isWithinRateLimit(containerName, rateLimitWindowMs)) {
            log.debug("Rate-limited AI for {} — using rule-based fallback", containerName);
            decision = ruleBasedDecision(stats);
        } else {
            ScalingDecision aiDecision = geminiService.analyzeAndPlan(stats);
            if (aiDecision != null) {
                decision = aiDecision;
                aiUsed   = true;
            } else {
                log.warn("AI returned null for {} — falling back to rule-based", containerName);
                decision = ruleBasedDecision(stats);
            }
            updateRateLimitTimestamp(containerName);
        }

        log.info("Decision for {}: action={} severity={} source={} reason={}",
                containerName,
                decision.getAction(),
                decision.getSeverity(),
                aiUsed ? "AI" : "RULE",
                decision.getReason());



        // ── 7. Publish scaling event to Kafka ─────────────────────
        int toReplicas = fromReplicas;
        if (decision.getAction() == ScalingDecision.Action.SCALE_UP)
            toReplicas = fromReplicas + 1;
        if (decision.getAction() == ScalingDecision.Action.SCALE_DOWN)
            toReplicas = Math.max(1, fromReplicas - 1);
        if (decision.getAction() != ScalingDecision.Action.NONE) {
            persistDecision(containerName, decision);
            kafkaProducer.publishScalingEvent(
                    containerName, decision, fromReplicas, toReplicas, aiUsed);
        }

        // ── 8. Prune old pgvector rows ────────────────────────────
        vectorStore.pruneOldData(containerName);
    }

    // ─────────────────────────────────────────────────────────────
    // Log error detection
    // ─────────────────────────────────────────────────────────────

    private void checkLogsAndAlert(ContainerStats stats) {
        String logs = stats.getRecentLogs();
        if (logs == null || logs.isBlank()) return;

        boolean hasError = logs.contains("ERROR")
                || logs.contains("Exception")
                || logs.contains("FATAL")
                || logs.contains("OOMKilled");

        if (hasError) {
            String summary = extractFirstErrorLine(logs);
            kafkaProducer.publishLogAlert(stats.getContainerName(), summary, logs);
            log.warn("Log alert published for {}: {}", stats.getContainerName(), summary);
        }
    }

    private String extractFirstErrorLine(String logs) {
        for (String line : logs.split("\n")) {
            if (line.contains("ERROR") || line.contains("Exception")
                    || line.contains("FATAL") || line.contains("OOMKilled")) {
                return line.length() > 200 ? line.substring(0, 200) : line;
            }
        }
        return "Error detected in logs";
    }

    // ─────────────────────────────────────────────────────────────
    // pgvector persistence
    // ─────────────────────────────────────────────────────────────

    private void persistSnapshot(ContainerStats stats) {
        try {
            String json = objectMapper.writeValueAsString(stats);
            vectorStore.saveSnapshot(stats.getContainerName(), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise ContainerStats for {}: {}",
                    stats.getContainerName(), e.getMessage());
        }
    }

    private void persistDecision(String containerName, ScalingDecision decision) {
        try {
            String json = objectMapper.writeValueAsString(decision);
            vectorStore.saveScalingEvent(containerName, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise ScalingDecision for {}: {}",
                    containerName, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Rule-based fallback
    // ─────────────────────────────────────────────────────────────

    private ScalingDecision ruleBasedDecision(ContainerStats stats) {
        String status = stats.getStatus();

        if ("exited".equalsIgnoreCase(status) || "dead".equalsIgnoreCase(status)) {
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.RESTART)
                    .reason("Container status is " + status)
                    .severity(ScalingDecision.Severity.HIGH)
                    .summary("Container is down — restarting")
                    .fixPlan(List.of(
                            "Capture current logs before restart",
                            "Restart the container via Docker API",
                            "Tail logs for 60s after restart",
                            "Alert on-call if restart fails"))
                    .build();
        }

        if (stats.getCpuPercent() > 70.0) {
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP)
                    .reason(String.format("CPU at %.1f%% exceeds 70%% threshold",
                            stats.getCpuPercent()))
                    .severity(stats.getCpuPercent() > 85
                            ? ScalingDecision.Severity.HIGH
                            : ScalingDecision.Severity.MEDIUM)
                    .summary("CPU pressure — scaling up")
                    .fixPlan(List.of(
                            "Verify load balancer health",
                            "Start one additional replica",
                            "Monitor CPU on new replica for 2 minutes",
                            "Alert if CPU remains above 70% after 5 minutes"))
                    .build();
        }

        if (stats.getMemoryPercent() > 80.0) {
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_UP)
                    .reason(String.format("Memory at %.1f%% exceeds 80%% threshold",
                            stats.getMemoryPercent()))
                    .severity(ScalingDecision.Severity.HIGH)
                    .summary("Memory pressure — scaling up")
                    .fixPlan(List.of(
                            "Check for memory leak indicators in logs",
                            "Start one additional replica",
                            "Monitor memory on new replica",
                            "Investigate heap usage if memory keeps climbing"))
                    .build();
        }

        if (stats.getCpuPercent() < 25.0 && stats.getMemoryPercent() < 40.0) {
            return ScalingDecision.builder()
                    .action(ScalingDecision.Action.SCALE_DOWN)
                    .reason(String.format("CPU %.1f%% and memory %.1f%% below thresholds",
                            stats.getCpuPercent(), stats.getMemoryPercent()))
                    .severity(ScalingDecision.Severity.LOW)
                    .summary("Low utilisation — scaling down")
                    .fixPlan(List.of(
                            "Drain in-flight requests from one replica",
                            "Wait 30s for graceful shutdown",
                            "Remove the replica",
                            "Verify remaining replicas handle traffic"))
                    .build();
        }

        return ScalingDecision.builder()
                .action(ScalingDecision.Action.NONE)
                .reason(String.format("CPU %.1f%%, memory %.1f%% — within normal bounds",
                        stats.getCpuPercent(), stats.getMemoryPercent()))
                .severity(ScalingDecision.Severity.LOW)
                .summary("System healthy — no action required")
                .fixPlan(List.of("Continue monitoring"))
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Rate-limiting helpers
    // ─────────────────────────────────────────────────────────────

    private boolean isWithinRateLimit(String containerName, long windowMs) {
        Long last = lastAiCallTime.get(containerName);
        if (last == null) return false;
        return (Instant.now().toEpochMilli() - last) < windowMs;
    }

    private void updateRateLimitTimestamp(String containerName) {
        lastAiCallTime.put(containerName, Instant.now().toEpochMilli());
    }

    private String extractName(com.github.dockerjava.api.model.Container container) {
        String[] names = container.getNames();
        if (names == null || names.length == 0) return container.getId();
        return names[0].startsWith("/") ? names[0].substring(1) : names[0];
    }
}