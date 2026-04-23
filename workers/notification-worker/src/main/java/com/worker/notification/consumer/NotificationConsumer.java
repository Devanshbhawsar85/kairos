package com.worker.notification.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worker.notification.service.SlackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationConsumer {

    private final SlackService   slackService;
    private final ObjectMapper   objectMapper = new ObjectMapper();

    public NotificationConsumer(SlackService slackService) {
        this.slackService = slackService;
    }

    // ── Consume scaling events ────────────────────────────────
    @KafkaListener(topics = "scaling-events",
            groupId = "notification-worker",
            containerFactory = "kafkaListenerContainerFactory")
    public void onScalingEvent(String message) {
        try {
            JsonNode event = objectMapper.readTree(message);
            String container = event.path("containerName").asText();
            String action    = event.path("action").asText();
            String reason    = event.path("reason").asText();
            String severity  = event.path("severity").asText();
            String summary   = event.path("summary").asText();
            int    from      = event.path("fromReplicas").asInt();
            int    to        = event.path("toReplicas").asInt();
            String source    = event.path("decisionSource").asText("RULE");

            // Build fix plan text
            StringBuilder fixPlan = new StringBuilder();
            JsonNode planNode = event.path("fixPlan");
            if (planNode.isArray()) {
                int i = 1;
                for (JsonNode step : planNode) {
                    fixPlan.append(i++).append(". ").append(step.asText()).append("\n");
                }
            }

            log.info("🔔 Notification: received scaling event — {} on {}", action, container);
            slackService.sendScalingAlert(
                    container, action, reason, severity,
                    summary, from, to, source, fixPlan.toString());

        } catch (Exception e) {
            log.error("Failed to process scaling event: {}", e.getMessage());
        }
    }

    // ── Consume log alerts ────────────────────────────────────
    @KafkaListener(topics = "log-alerts",
            groupId = "notification-worker",
            containerFactory = "kafkaListenerContainerFactory")
    public void onLogAlert(String message) {
        try {
            JsonNode event   = objectMapper.readTree(message);
            String container = event.path("containerName").asText();
            String summary   = event.path("errorSummary").asText();
            String logs      = event.path("recentLogs").asText();

            log.info("🔔 Notification: received log alert for {}", container);
            slackService.sendLogAlert(container, summary, logs);

        } catch (Exception e) {
            log.error("Failed to process log alert: {}", e.getMessage());
        }
    }
}