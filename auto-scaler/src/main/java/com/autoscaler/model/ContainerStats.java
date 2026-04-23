package com.autoscaler.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContainerStats {
    private String containerId;
    private String containerName;
    private double cpuPercent;
    private double memoryPercent;
    private long   memoryUsed;
    private long   memoryLimit;
    private String status;
    private long   timestamp;
    private long   networkRxBytes;
    private long   networkTxBytes;
    private String recentLogs;
}