package com.autoscaler.controller;

import com.autoscaler.service.AutoScalerService;
import com.autoscaler.service.DockerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/autoscaler")
public class AutoScalerController {

    private final AutoScalerService autoScalerService;
    private final DockerService     dockerService;

    public AutoScalerController(AutoScalerService autoScalerService,
                                DockerService dockerService) {
        this.autoScalerService = autoScalerService;
        this.dockerService     = dockerService;
    }

    /** Current state of all managed containers. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        var containers = dockerService.listContainers();
        var statsList  = containers.stream()
                .map(c -> dockerService.getContainerStats(c.getId()))
                .filter(s -> s != null)
                .collect(Collectors.toList());

        return Map.of(
                "currentReplicas", dockerService.getCurrentReplicas(),
                "containers",      statsList,
                "timestamp",       System.currentTimeMillis()
        );
    }

    /** Trigger a full monitoring cycle immediately (for testing / manual ops). */
    @PostMapping("/trigger")
    public Map<String, String> trigger() {
        log.info("Manual monitoring trigger via REST");
        autoScalerService.monitorAndScale();
        return Map.of("result", "ok", "message", "Monitoring cycle executed");
    }

    /** Manually scale up by 1 replica. */
    @PostMapping("/scale-up")
    public Map<String, Object> scaleUp() {
        boolean ok = dockerService.scaleUp(1);
        return Map.of("success", ok, "replicas", dockerService.getCurrentReplicas());
    }

    /** Manually scale down by 1 replica. */
    @PostMapping("/scale-down")
    public Map<String, Object> scaleDown() {
        boolean ok = dockerService.scaleDown(1);
        return Map.of("success", ok, "replicas", dockerService.getCurrentReplicas());
    }

    /** Health check — also used by Docker HEALTHCHECK in Dockerfile. */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "Auto-Scaler");
    }
}