package com.worker.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AnalyticsWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsWorkerApplication.class, args);
        System.out.println("""
                ╔══════════════════════════════════════╗
                ║   📊 Analytics Worker Started!       ║
                ║   Writing events to PostgreSQL...    ║
                ╚══════════════════════════════════════╝
                """);
    }
}