package com.worker.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;

    public AnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ─────────────────────────────────────────────
    //  Store scaling event
    // ─────────────────────────────────────────────

    @Transactional
    public void storeScalingEvent(JsonNode event) {
        try {
            // Build fix_plan as a single text string from the array
            JsonNode planNode = event.path("fixPlan");
            StringBuilder fixPlan = new StringBuilder();
            if (planNode.isArray()) {
                int step = 1;
                for (JsonNode s : planNode) {
                    fixPlan.append(step++).append(". ").append(s.asText()).append("\n");
                }
            }

            Long historyId = jdbc.queryForObject("""
                INSERT INTO scaling_history
                    (container, action, reason, severity, summary, fix_plan)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                    Long.class,
                    event.path("containerName").asText(),
                    event.path("action").asText(),
                    event.path("reason").asText(),
                    event.path("severity").asText(),
                    event.path("summary").asText(),
                    fixPlan.toString()
            );

            log.info("📊 Analytics: stored scaling event id={}", historyId);

        } catch (Exception e) {
            log.error("Failed to store scaling event in PostgreSQL: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Store metrics update (aggregated by hour)
    // ─────────────────────────────────────────────

    public void storeMetricsUpdate(JsonNode event) {
        try {
            String container = event.path("containerName").asText();
            double cpu       = event.path("cpuPercent").asDouble();
            double memory    = event.path("memoryPercent").asDouble();

            // Upsert hourly aggregate
            jdbc.update("""
                INSERT INTO metrics_hourly
                    (container, hour_bucket, avg_cpu, max_cpu,
                     avg_memory, max_memory, sample_count)
                VALUES (?, DATE_TRUNC('hour', NOW()), ?, ?, ?, ?, 1)
                ON CONFLICT (container, hour_bucket)
                DO UPDATE SET
                    avg_cpu      = (metrics_hourly.avg_cpu * metrics_hourly.sample_count + ?)
                                   / (metrics_hourly.sample_count + 1),
                    max_cpu      = GREATEST(metrics_hourly.max_cpu, ?),
                    avg_memory   = (metrics_hourly.avg_memory * metrics_hourly.sample_count + ?)
                                   / (metrics_hourly.sample_count + 1),
                    max_memory   = GREATEST(metrics_hourly.max_memory, ?),
                    sample_count = metrics_hourly.sample_count + 1
                """,
                    container, cpu, cpu, memory, memory,
                    cpu, cpu, memory, memory
            );

        } catch (Exception e) {
            log.error("Failed to store metrics update: {}", e.getMessage());
        }
    }
}