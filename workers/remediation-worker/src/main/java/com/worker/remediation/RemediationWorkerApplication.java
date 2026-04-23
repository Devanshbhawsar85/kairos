package com.worker.remediation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync  // ✅ Required for @Async on handleScaleUp/Down/Restart
public class RemediationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RemediationWorkerApplication.class, args);
    }
}