package com.worker.analytics.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worker.analytics.service.AnalyticsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AnalyticsConsumer {

    private final AnalyticsService analyticsService;
    private final ObjectMapper     objectMapper = new ObjectMapper();

    public AnalyticsConsumer(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @KafkaListener(topics = "scaling-events",
            groupId = "analytics-worker")
    public void onScalingEvent(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            log.info("📊 Analytics: storing scaling event for {}",
                    event.path("containerName").asText());
            analyticsService.storeScalingEvent(event);
        } catch (Exception e) {
            log.error("Analytics failed to process scaling event: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "metrics-updates",
            groupId = "analytics-worker")
    public void onMetricsUpdate(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            analyticsService.storeMetricsUpdate(event);
        } catch (Exception e) {
            log.error("Analytics failed to process metrics update: {}", e.getMessage());
        }
    }
}