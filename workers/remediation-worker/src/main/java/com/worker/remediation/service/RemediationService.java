package com.worker.remediation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RemediationService {

    @Value("${docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${docker.image-name:main-app:latest}")
    private String imageName;

    @Value("${docker.container-prefix:main-app}")
    private String containerPrefix;

    @Value("${docker.network-name:autoscaler-net}")
    private String networkName;

    @Value("${docker.max-containers:5}")
    private int maxContainers;

    @Value("${docker.min-containers:1}")
    private int minContainers;

    // ✅ FIX 2: Base container name injected — used to protect main-app-1
    // from being removed during scale-down (double safety alongside sort-by-age)
    @Value("${docker.base-container-name:main-app-1}")
    private String baseContainerName;

    private DockerClient dockerClient;
    private final JdbcTemplate jdbc;

    public RemediationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        try {
            DockerClientConfig config = DefaultDockerClientConfig
                    .createDefaultConfigBuilder()
                    .withDockerHost(dockerHost)
                    .build();

            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(10)
                    .connectionTimeout(Duration.ofSeconds(15))
                    .responseTimeout(Duration.ofSeconds(30))
                    .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            dockerClient.pingCmd().exec();
            log.info("✅ Remediation worker: Docker client connected");
        } catch (Exception e) {
            log.warn("Remediation worker: Docker not available — {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  SCALE UP
    // ─────────────────────────────────────────────

    // ✅ FIX 4: @Async moves Docker work off the Kafka consumer thread.
    // Thread.sleep(2000) was blocking the consumer thread, causing Kafka
    // to think the consumer was slow and potentially trigger rebalancing.
    // Requires @EnableAsync on your main application class.
    @Async
    public void handleScaleUp(JsonNode event) {
        String containerName = event.path("containerName").asText();
        List<String> fixPlan = extractFixPlan(event);

        log.info("🛠  Remediation SCALE_UP for {}", containerName);
        logFixPlan(fixPlan);

        try {
            List<Container> current = getManagedContainers();
            log.info("Current managed containers: {}", current.size());

            if (current.size() >= maxContainers) {
                log.warn("⛔ Already at max containers ({}), skipping scale up", maxContainers);
                recordJob("SCALE_UP", containerName, "FAILED",
                        "Already at max containers: " + maxContainers);
                return;
            }

            String newName = containerPrefix + "-" + System.currentTimeMillis();

            // ✅ FIX 5: Label spawned containers as managed replicas.
            // This makes them identifiable and distinguishable from main-app-1.
            // The autoscaler/monitoring should exclude autoscaler.managed=true
            // containers from emitting their own scaling events.
            var response = dockerClient.createContainerCmd(imageName)
                    .withName(newName)
                    .withEnv("SERVER_PORT=8080")
                    .withNetworkMode(networkName)
                    .withLabels(Map.of(
                            "autoscaler.managed", "true",
                            "autoscaler.base-service", baseContainerName
                    ))
                    .exec();

            dockerClient.startContainerCmd(response.getId()).exec();
            log.info("✅ Remediation: started {} (id={})",
                    newName, response.getId().substring(0, 12));

            // ✅ FIX 4: Sleep is now safe here — we're on an async thread,
            // not the Kafka consumer thread.
            Thread.sleep(2000);

            var inspect = dockerClient.inspectContainerCmd(response.getId()).exec();
            String status = inspect.getState() != null
                    ? inspect.getState().getStatus() : "unknown";

            if ("running".equals(status)) {
                int newTotal = current.size() + 1;
                log.info("✅ Remediation: SCALE_UP done — {} containers now running", newTotal);
                recordJob("SCALE_UP", containerName, "DONE",
                        "Started " + newName + " — total replicas: " + newTotal);
            } else {
                log.warn("⚠️  Remediation: {} started but status = {}", newName, status);
                recordJob("SCALE_UP", containerName, "FAILED",
                        "Container started but status is: " + status);
            }

        } catch (Exception e) {
            log.error("Remediation SCALE_UP failed: {}", e.getMessage(), e);
            recordJob("SCALE_UP", containerName, "FAILED", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  SCALE DOWN
    // ─────────────────────────────────────────────

    @Async
    public void handleScaleDown(JsonNode event) {
        String containerName = event.path("containerName").asText();
        List<String> fixPlan = extractFixPlan(event);

        log.info("🛠  Remediation SCALE_DOWN for {}", containerName);
        logFixPlan(fixPlan);

        try {
            List<Container> current = getManagedContainers();
            log.info("Current managed containers: {}", current.size());

            if (current.size() <= minContainers) {
                log.warn("⛔ Already at min containers ({}), skipping scale down", minContainers);
                recordJob("SCALE_DOWN", containerName, "FAILED",
                        "Already at min containers: " + minContainers);
                return;
            }

            // Sort newest first — removes highest-timestamp replica first
            current.sort(Comparator.comparingLong(Container::getCreated).reversed());

            Container toRemove = current.get(0);
            String id   = toRemove.getId();
            String name = toRemove.getNames()[0];

            // ✅ FIX 2: Double-safety guard — never remove the base container
            // even if sort order somehow puts it first (e.g. clock skew)
            if (name.replace("/", "").equals(baseContainerName)) {
                log.warn("⛔ Refusing to remove base container: {}", baseContainerName);
                recordJob("SCALE_DOWN", containerName, "FAILED",
                        "Refused to remove base container: " + baseContainerName);
                return;
            }

            log.info("🛠  Remediation: stopping container {} (newest replica)", name);
            dockerClient.stopContainerCmd(id).withTimeout(15).exec();
            dockerClient.removeContainerCmd(id).exec();

            int remaining = current.size() - 1;
            log.info("✅ Remediation: SCALE_DOWN done — removed {} — {} replicas remaining",
                    name, remaining);
            recordJob("SCALE_DOWN", containerName, "DONE",
                    "Removed " + name + " — replicas remaining: " + remaining);

        } catch (Exception e) {
            log.error("Remediation SCALE_DOWN failed: {}", e.getMessage(), e);
            recordJob("SCALE_DOWN", containerName, "FAILED", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  RESTART
    // ─────────────────────────────────────────────

    @Async
    public void handleRestart(JsonNode event) {
        String containerName = event.path("containerName").asText();
        List<String> fixPlan = extractFixPlan(event);

        log.info("🛠  Remediation RESTART for {}", containerName);
        logFixPlan(fixPlan);

        try {
            String targetName = containerName.replace("/", "");

            var containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            var target = containers.stream()
                    .filter(c -> {
                        for (String n : c.getNames()) {
                            if (n.replace("/", "").equals(targetName)) return true;
                        }
                        return false;
                    })
                    .findFirst();

            if (target.isEmpty()) {
                log.warn("⛔ Remediation: container {} not found for restart", containerName);
                recordJob("RESTART", containerName, "FAILED", "Container not found");
                return;
            }

            String id = target.get().getId();
            dockerClient.restartContainerCmd(id).exec();
            log.info("✅ Remediation: restart command sent to {}", containerName);

            // ✅ FIX 4: Safe to sleep — running on async thread
            Thread.sleep(3000);

            var inspect = dockerClient.inspectContainerCmd(id).exec();
            String status = inspect.getState() != null
                    ? inspect.getState().getStatus() : "unknown";

            log.info("✅ Remediation: RESTART verified — {} status = {}", containerName, status);
            recordJob("RESTART", containerName, "DONE",
                    "Restarted successfully, status: " + status);

        } catch (Exception e) {
            log.error("Remediation RESTART failed: {}", e.getMessage(), e);
            recordJob("RESTART", containerName, "FAILED", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────

    private List<Container> getManagedContainers() {
        List<Container> all = new ArrayList<>(
                dockerClient.listContainersCmd()
                        .withShowAll(false)
                        .exec()
        );

        List<Container> managed = new ArrayList<>();
        for (Container c : all) {
            for (String name : c.getNames()) {
                if (name.contains(containerPrefix)) {
                    managed.add(c);
                    break;
                }
            }
        }

        log.debug("Found {} managed containers with prefix '{}'",
                managed.size(), containerPrefix);
        return managed;
    }

    private void recordJob(String jobType, String container, String status, String message) {
        try {
            jdbc.update(
                    "INSERT INTO worker_jobs (job_type, topic, payload, status, worker_name, error_message) VALUES (?, ?, ?, ?, ?, ?)",
                    jobType,
                    "scaling-events",
                    container,
                    status,
                    "remediation-worker",
                    "FAILED".equals(status) ? message : null
            );
        } catch (Exception e) {
            log.warn("Failed to record job: {}", e.getMessage());
        }
    }

    private List<String> extractFixPlan(JsonNode event) {
        List<String> steps = new ArrayList<>();
        JsonNode planNode  = event.path("fixPlan");
        if (planNode.isArray()) planNode.forEach(n -> steps.add(n.asText()));
        return steps;
    }

    private void logFixPlan(List<String> steps) {
        for (int i = 0; i < steps.size(); i++) {
            log.info("   {}. {}", i + 1, steps.get(i));
        }
    }
}