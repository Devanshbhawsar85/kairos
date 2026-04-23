package com.autoscaler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AutoScalerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AutoScalerApplication.class, args);
        System.out.println("""
                
                ╔══════════════════════════════════════╗
                ║   🐳 Docker Auto-Scaler Started!     ║
                ║   Monitoring Java Applications       ║
                ╚══════════════════════════════════════╝
                """);
    }
}

