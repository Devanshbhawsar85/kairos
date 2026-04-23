package com.worker.remediation.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worker.remediation.service.RemediationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RemediationConsumer {

    private final RemediationService remediationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ FIX 2: Only process events for the base container name.
    // Dynamically spawned replicas (main-app-17756XXXXXXXXX) must never
    // trigger their own scaling — that causes the runaway loop seen in logs.
    @Value("${docker.base-container-name:main-app-1}")
    private String baseContainerName;

    // ✅ FIX 3: Per-container cooldown to prevent rapid repeated scaling.
    // Key = containerName, Value = last action timestamp in ms.
    private final ConcurrentHashMap<String, Long> lastActionTime = new ConcurrentHashMap<>();

    @Value("${remediation.cooldown-ms:60000}")
    private long cooldownMs;

    public RemediationConsumer(RemediationService remediationService) {
        this.remediationService = remediationService;
    }

    @KafkaListener(topics = "scaling-events", groupId = "remediation-worker")
    public void onScalingEvent(String message) {
        try {
            JsonNode event   = objectMapper.readTree(message);
            String action    = event.path("action").asText();
            String container = event.path("containerName").asText();

            log.info("🛠  Remediation: received {} for {}", action, container);

            // ✅ FIX 2: Ignore events targeting dynamically spawned replicas.
            // Only the base container (main-app-1) should trigger scaling decisions.
            // Replicas are named main-app-<timestamp> and must be skipped.
            if (!container.equals(baseContainerName)) {
                log.info("⏭  Skipping event for replica container: {} " +
                        "(only '{}' triggers scaling)", container, baseContainerName);
                return;
            }

            // ✅ FIX 3: Enforce cooldown between actions on the same container.
            // Prevents 3 SCALE_DOWN events in 45s (as seen in logs) from all executing.
            long now  = System.currentTimeMillis();
            long last = lastActionTime.getOrDefault(container, 0L);
            if (now - last < cooldownMs) {
                long remainingSeconds = (cooldownMs - (now - last)) / 1000;
                log.info("⏳ Cooldown active for {} — skipping {} ({}s remaining)",
                        container, action, remainingSeconds);
                return;
            }

            // Skip NONE actions — no cooldown update needed
            if ("NONE".equals(action)) {
                log.debug("No remediation needed for action: {}", action);
                return;
            }

            // Record cooldown timestamp BEFORE acting to block concurrent events
            lastActionTime.put(container, now);

            switch (action) {
                case "RESTART"    -> remediationService.handleRestart(event);
                case "SCALE_UP"   -> remediationService.handleScaleUp(event);
                case "SCALE_DOWN" -> remediationService.handleScaleDown(event);
                default           -> log.debug("No remediation needed for action: {}", action);
            }

        } catch (Exception e) {
            log.error("Remediation failed to process event: {}", e.getMessage(), e);
        }
    }
}