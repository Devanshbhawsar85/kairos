package com.autoscaler.service;

import com.autoscaler.model.ContainerStats;
import com.autoscaler.model.ScalingDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Publishes events to Kafka topics.
 *
 * Topics:
 *   scaling-events  → consumed by notification + analytics + remediation workers
 *   log-alerts      → consumed by notification worker
 *   metrics-updates → consumed by analytics worker
 */
@Slf4j
@Service
public class KafkaProducerService {

    public static final String TOPIC_SCALING  = "scaling-events";
    public static final String TOPIC_LOGS     = "log-alerts";
    public static final String TOPIC_METRICS  = "metrics-updates";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    // ─────────────────────────────────────────────
    //  Publish scaling event
    // ─────────────────────────────────────────────

    public void publishScalingEvent(String containerName,
                                    ScalingDecision decision,
                                    int fromReplicas,
                                    int toReplicas,
                                    boolean aiUsed) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type",          "SCALING_EVENT");
            payload.put("containerName", containerName);
            payload.put("action",        decision.getAction().name());
            payload.put("reason",        decision.getReason());
            payload.put("severity",      decision.getSeverity().name());
            payload.put("summary",       decision.getSummary() != null ? decision.getSummary() : "");
            payload.put("fromReplicas",  fromReplicas);
            payload.put("toReplicas",    toReplicas);
            payload.put("fixPlan",       decision.getFixPlan());
            payload.put("decisionSource", aiUsed ? "AI" : "RULE");
            payload.put("timestamp",     Instant.now().toString());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(TOPIC_SCALING, containerName, json);
            log.info("📤 Kafka: published scaling event — {} for {}",
                    decision.getAction(), containerName);
        } catch (Exception e) {
            log.warn("Kafka publishScalingEvent failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Publish log alert
    // ─────────────────────────────────────────────

    public void publishLogAlert(String containerName,
                                String errorSummary,
                                String recentLogs) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type",          "LOG_ALERT");
            payload.put("containerName", containerName);
            payload.put("errorSummary",  errorSummary);
            payload.put("recentLogs",    recentLogs != null
                    ? recentLogs.substring(Math.max(0, recentLogs.length() - 300))
                    : "");
            payload.put("timestamp",     Instant.now().toString());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(TOPIC_LOGS, containerName, json);
            log.info("📤 Kafka: published log alert for {}", containerName);
        } catch (Exception e) {
            log.warn("Kafka publishLogAlert failed: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Publish metrics update
    // ─────────────────────────────────────────────

    public void publishMetricsUpdate(ContainerStats stats) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type",          "METRICS_UPDATE");
            payload.put("containerName", stats.getContainerName());
            payload.put("cpuPercent",    stats.getCpuPercent());
            payload.put("memoryPercent", stats.getMemoryPercent());
            payload.put("memoryUsedMb",  stats.getMemoryUsed() / (1024 * 1024));
            payload.put("status",        stats.getStatus());
            payload.put("networkRxKb",   stats.getNetworkRxBytes() / 1024);
            payload.put("networkTxKb",   stats.getNetworkTxBytes() / 1024);
            payload.put("timestamp",     Instant.now().toString());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(TOPIC_METRICS, stats.getContainerName(), json);
        } catch (Exception e) {
            log.warn("Kafka publishMetricsUpdate failed: {}", e.getMessage());
        }
    }
}