package com.autoscaler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AppConfig {

    @Bean
    @ConfigurationProperties(prefix = "autoscaler")
    public AutoscalerProperties autoscalerProperties() {
        return new AutoscalerProperties();
    }

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // ── Property groups ───────────────────────────────────────────

    @Data
    public static class AutoscalerProperties {
        private DockerProps      docker      = new DockerProps();
        private ScalingProps     scaling     = new ScalingProps();
        private MonitoringProps  monitoring  = new MonitoringProps();
        private WebhookProps     webhook     = new WebhookProps();
        private VectorStoreProps vectorStore = new VectorStoreProps();
    }

    @Data
    public static class DockerProps {
        private String imageName       = "main-app:latest";
        private String containerPrefix = "main-app";
        private int    minContainers   = 1;
        private int    maxContainers   = 5;
        private String dockerHost      = "unix:///var/run/docker.sock";
        private String networkName     = "autoscaler-net";
    }

    @Data
    public static class ScalingProps {
        private double cpuThreshold    = 70.0;
        private double memoryThreshold = 80.0;
        private int    cooldownSeconds = 120;
    }

    @Data
    public static class MonitoringProps {
        private int intervalSeconds = 30;
        private int logSampleLines  = 100;
    }

    @Data
    public static class WebhookProps {
        private String  url             = "";
        private boolean enabled         = false;
        private int     cooldownSeconds = 60;
        private int     maxPerHour      = 10;
    }

    /**
     * Tuning knobs for pgvector RAG store.
     * Bound from autoscaler.vector-store.* in application.yml.
     */
    @Data
    public static class VectorStoreProps {
        private int snapshotRetention = 500;   // max rows kept per container
        private int eventRetention    = 200;
        private int topKSnapshots     = 20;    // rows returned per similarity search
        private int topKEvents        = 10;
    }
}