package com.worker.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SlackService {

    @Value("${slack.webhook-url:}")
    private String webhookUrl;

    private final WebClient webClient;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    public SlackService(WebClient webClient) {
        this.webClient = webClient;
    }

    // ─────────────────────────────────────────────
    //  Scaling alert
    // ─────────────────────────────────────────────

    public void sendScalingAlert(String container, String action,
                                 String reason, String severity,
                                 String summary, int from, int to,
                                 String source, String fixPlan) {
        if (!isConfigured()) return;

        String emoji = switch (action) {
            case "SCALE_UP"   -> "⬆️";
            case "SCALE_DOWN" -> "⬇️";
            case "RESTART"    -> "🔄";
            default           -> "ℹ️";
        };
        String color = switch (severity) {
            case "HIGH"   -> "#ff0000";
            case "MEDIUM" -> "#ff9900";
            default       -> "#36a64f";
        };

        String text = String.format(
                "%s *Auto-Scaler: %s on %s*\n" +
                        ">*Summary:* %s\n" +
                        ">*Reason:* %s\n" +
                        ">*Replicas:* %d → %d\n" +
                        ">*Decision:* %s\n" +
                        ">*Time:* %s",
                emoji, action, container,
                summary, reason, from, to,
                source, FMT.format(Instant.now())
        );

        String footer = fixPlan != null && !fixPlan.isBlank()
                ? "🛠 *Fix Plan:*\n" + fixPlan : null;

        send(color, text, footer);
    }

    // ─────────────────────────────────────────────
    //  Log alert
    // ─────────────────────────────────────────────

    public void sendLogAlert(String container, String summary, String logs) {
        if (!isConfigured()) return;

        String text = String.format(
                "🚨 *Log Alert: %s*\n>%s\n>*Time:* %s",
                container, summary, FMT.format(Instant.now())
        );
        String footer = logs != null && !logs.isBlank()
                ? "Recent logs:\n```" + logs.substring(
                Math.max(0, logs.length() - 200)) + "```"
                : null;

        send("#ff0000", text, footer);
    }

    // ─────────────────────────────────────────────
    //  HTTP send
    // ─────────────────────────────────────────────

    private void send(String color, String text, String footer) {
        try {
            var attachment = new java.util.LinkedHashMap<String, Object>();
            attachment.put("color", color);
            attachment.put("text",  text);
            attachment.put("mrkdwn_in", List.of("text", "footer"));
            if (footer != null) attachment.put("footer", footer);

            Map<String, Object> payload = Map.of(
                    "attachments", List.of(attachment)
            );

            webClient.post()
                    .uri(webhookUrl)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            r -> log.info("📣 Slack notification sent"),
                            e -> log.warn("Slack send failed: {}", e.getMessage())
                    );
        } catch (Exception e) {
            log.error("SlackService send error: {}", e.getMessage());
        }
    }

    private boolean isConfigured() {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Slack webhook not configured — skipping");
            return false;
        }
        return true;
    }
}