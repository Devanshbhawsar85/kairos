package com.autoscaler.scheduler;

import com.autoscaler.service.AutoScalerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MonitoringScheduler {

    private final AutoScalerService autoScalerService;

    public MonitoringScheduler(AutoScalerService autoScalerService) {
        this.autoScalerService = autoScalerService;
    }

    /**
     * Runs every ${autoscaler.monitoring.interval-seconds} seconds.
     * fixedDelayString ensures a full interval between the END of one
     * run and the START of the next — preventing cycle overlap.
     */
    @Scheduled(fixedDelayString = "${autoscaler.monitoring.interval-seconds:30}000",
            initialDelayString = "10000")
    public void monitorContainers() {
        log.debug("Monitoring cycle started");
        try {
            autoScalerService.monitorAndScale();
        } catch (Exception e) {
            log.error("Monitoring cycle failed: {}", e.getMessage(), e);
        }
    }
}