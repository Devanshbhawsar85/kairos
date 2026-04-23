package com.autoscaler.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ReplicaStateService — reads the actual running replica count
 * directly from Docker instead of relying on an in-memory counter.
 *
 * Why this exists:
 *   The remediation-worker is the one starting/stopping containers.
 *   The auto-scaler never calls scaleUp/scaleDown directly anymore.
 *   So any in-memory counter in the auto-scaler would be stale.
 *
 *   Solution: auto-scaler asks Docker directly "how many containers
 *   matching my prefix are currently running?" — this is always accurate
 *   regardless of who started or stopped them.
 */
@Slf4j
@Service
public class ReplicaStateService {

    private final DockerService dockerService;

    @Value("${autoscaler.docker.min-containers:1}")
    private int minContainers;

    @Value("${autoscaler.docker.max-containers:5}")
    private int maxContainers;

    public ReplicaStateService(DockerService dockerService) {
        this.dockerService = dockerService;
    }

    /**
     * Returns the ACTUAL number of running managed containers
     * by querying Docker directly. Never stale.
     */
    public int getCurrentReplicas() {
        try {
            int count = dockerService.listContainers().size();
            log.debug("Current replicas from Docker: {}", count);
            return Math.max(count, 1); // at least 1
        } catch (Exception e) {
            log.warn("Could not get replica count from Docker: {}", e.getMessage());
            return 1;
        }
    }

    public int getMinContainers() { return minContainers; }
    public int getMaxContainers() { return maxContainers; }

    /**
     * Can we scale up?
     */
    public boolean canScaleUp() {
        return getCurrentReplicas() < maxContainers;
    }

    /**
     * Can we scale down?
     */
    public boolean canScaleDown() {
        return getCurrentReplicas() > minContainers;
    }
}