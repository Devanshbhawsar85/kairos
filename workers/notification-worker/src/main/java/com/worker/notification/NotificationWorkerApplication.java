package com.worker.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationWorkerApplication.class, args);
        System.out.println("""
                ╔══════════════════════════════════════╗
                ║   🔔 Notification Worker Started!    ║
                ║   Listening to Kafka topics...       ║
                ╚══════════════════════════════════════╝
                """);
    }
}