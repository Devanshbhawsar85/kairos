package com.myapp.controller;

import com.myapp.service.BusinessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RestController
@RequestMapping("/api")
public class ApiController {

    private final BusinessService businessService;
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong errorCount   = new AtomicLong(0);

    public ApiController(BusinessService businessService) {
        this.businessService = businessService;
    }

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        long count = requestCount.incrementAndGet();
        log.info("Request #{} received", count);
        return Map.of(
                "message",       "Hello from Java Spring Boot!",
                "requestNumber", count,
                "containerId",   System.getenv().getOrDefault("HOSTNAME", "local"),
                "timestamp",     System.currentTimeMillis()
        );
    }

    @GetMapping("/compute")
    public Map<String, Object> compute(@RequestParam(defaultValue = "100") int iterations) {
        long start = System.currentTimeMillis();
        long count = requestCount.incrementAndGet();
        try {
            double result = businessService.doComplexComputation(iterations);
            long duration = System.currentTimeMillis() - start;
            log.info("Computation completed in {}ms", duration);
            return Map.of(
                    "result",        result,
                    "duration",      duration,
                    "iterations",    iterations,
                    "requestNumber", count,
                    "containerId",   System.getenv().getOrDefault("HOSTNAME", "local")
            );
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in computation: {}", e.getMessage());
            throw new RuntimeException("Computation failed", e);
        }
    }

    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        return Map.of(
                "totalRequests", requestCount.get(),
                "totalErrors",   errorCount.get(),
                "errorRate",     requestCount.get() > 0
                        ? (double) errorCount.get() / requestCount.get() * 100 : 0,
                "activeThreads", Thread.activeCount(),
                "containerId",   System.getenv().getOrDefault("HOSTNAME", "local"),
                "timestamp",     System.currentTimeMillis()
        );
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status",      "UP",
                "service",     "Main Application",
                "containerId", System.getenv().getOrDefault("HOSTNAME", "local")
        );
    }

    @PostMapping("/heavy-task")
    public Map<String, Object> heavyTask(@RequestBody Map<String, Object> payload) {
        long start = System.currentTimeMillis();
        long count = requestCount.incrementAndGet();
        int dataSize = (int) payload.getOrDefault("size", 1000);
        double result = businessService.processLargeDataset(dataSize);
        long duration = System.currentTimeMillis() - start;
        log.warn("Heavy task completed in {}ms — this might trigger scaling", duration);
        return Map.of(
                "result",        result,
                "duration",      duration,
                "dataSize",      dataSize,
                "requestNumber", count,
                "containerId",   System.getenv().getOrDefault("HOSTNAME", "local")
        );
    }
}