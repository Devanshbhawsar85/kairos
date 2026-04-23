package com.myapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MainApplication {
    public static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
        System.out.println("""
                
                ╔══════════════════════════════════════╗
                ║   🚀 Main Application Started!       ║
                ║   Java Spring Boot App               ║
                ╚══════════════════════════════════════╝
                """);
    }
}