package com.autoscaler.service;

import com.autoscaler.config.AppConfig;
import com.autoscaler.model.ContainerStats;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DockerService {

    private final AppConfig.AutoscalerProperties props;
    private DockerClient dockerClient;

    // ✅ FIX 1: Remove currentReplicas cache entirely
    // private int currentReplicas;  // DELETE THIS LINE

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> logBuffers = new ConcurrentHashMap<>();

    // ✅ FIX 2: Track callbacks by container ID for proper cleanup
    private final ConcurrentHashMap<String, ResultCallback.Adapter<Frame>> logCallbacks = new ConcurrentHashMap<>();

    private static final int LOG_BUFFER_SIZE = 200;

    public DockerService(AppConfig.AutoscalerProperties props) {
        this.props = props;
        // ✅ Remove currentReplicas initialization
    }

    @PostConstruct
    public void init() {
        try {
            DockerClientConfig config = DefaultDockerClientConfig
                    .createDefaultConfigBuilder()
                    .withDockerHost(props.getDocker().getDockerHost())
                    .build();

            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
            dockerClient.pingCmd().exec();

            log.info("✅ Docker client connected successfully");
            listContainers().forEach(c -> startLogStream(c.getId()));

        } catch (Exception e) {
            log.error("❌ Failed to connect to Docker daemon: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        // ✅ FIX 3: Close all log callbacks properly
        logCallbacks.values().forEach(cb -> {
            try {
                cb.close();
            } catch (Exception ignored) {}
        });
        logCallbacks.clear();
        logBuffers.clear();

        try {
            if (dockerClient != null) dockerClient.close();
        } catch (Exception e) {
            log.warn("Error closing Docker client: {}", e.getMessage());
        }
    }

    public List<Container> listContainers() {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(false)
                    .exec()
                    .stream()
                    .filter(c -> {
                        for (String name : c.getNames()) {
                            if (name.contains(props.getDocker().getContainerPrefix())) return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error listing containers: {}", e.getMessage());
            return List.of();
        }
    }

    public void startLogStream(String containerId) {
        String shortId = shortId(containerId);
        logBuffers.putIfAbsent(shortId, new ConcurrentLinkedDeque<>());

        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                String line = new String(frame.getPayload()).stripTrailing();
                if (line.isBlank()) return;
                ConcurrentLinkedDeque<String> buf = logBuffers.get(shortId);
                if (buf != null) {
                    buf.addLast(line);
                    while (buf.size() > LOG_BUFFER_SIZE) buf.pollFirst();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                log.warn("Log stream error for {}: {}", shortId, throwable.getMessage());
            }
        };

        try {
            dockerClient.logContainerCmd(containerId)
                    .withFollowStream(true)
                    .withTail(50)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTimestamps(true)
                    .exec(callback);

            // ✅ Store callback by container ID
            logCallbacks.put(shortId, callback);
            log.info("📡 Log stream started for container {}", shortId);
        } catch (Exception e) {
            log.error("Failed to start log stream for {}: {}", shortId, e.getMessage());
        }
    }

    public String getBufferedLogs(String containerId) {
        String shortId = shortId(containerId);
        ConcurrentLinkedDeque<String> buf = logBuffers.get(shortId);
        if (buf != null && !buf.isEmpty()) {
            return String.join("\n", buf);
        }
        return getLogsTail(containerId, 50);
    }

    private String getLogsTail(String containerId, int lines) {
        try {
            List<String> collected = new ArrayList<>();
            ResultCallback.Adapter<Frame> cb = new ResultCallback.Adapter<>() {
                @Override public void onNext(Frame f) { collected.add(new String(f.getPayload())); }
            };
            dockerClient.logContainerCmd(containerId)
                    .withTail(lines).withStdOut(true).withStdErr(true).exec(cb);
            cb.awaitCompletion(5, TimeUnit.SECONDS);
            return String.join("", collected);
        } catch (Exception e) {
            return "Unable to fetch logs: " + e.getMessage();
        }
    }

    public ContainerStats getContainerStats(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            String status = inspect.getState() != null ? inspect.getState().getStatus() : "unknown";

            AtomicReference<Statistics> statRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            ResultCallback.Adapter<Statistics> statsCb = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Statistics stats) {
                    statRef.set(stats);
                    latch.countDown();
                }
            };
            dockerClient.statsCmd(containerId).withNoStream(true).exec(statsCb);
            latch.await(5, TimeUnit.SECONDS);

            Statistics stats = statRef.get();
            double cpuPercent = 0.0;
            double memoryPercent = 0.0;
            long memUsed = 0;
            long memLimit = 1;
            long rxBytes = 0;
            long txBytes = 0;

            if (stats != null) {
                // CPU calculation
                var cpuStats = stats.getCpuStats();
                var preCpu = stats.getPreCpuStats();
                if (cpuStats != null && preCpu != null
                        && cpuStats.getCpuUsage() != null && preCpu.getCpuUsage() != null) {
                    long cpuDelta = cpuStats.getCpuUsage().getTotalUsage()
                            - preCpu.getCpuUsage().getTotalUsage();
                    long sysDelta = (cpuStats.getSystemCpuUsage() != null ? cpuStats.getSystemCpuUsage() : 0L)
                            - (preCpu.getSystemCpuUsage() != null ? preCpu.getSystemCpuUsage() : 0L);
                    int numCpus = cpuStats.getCpuUsage().getPercpuUsage() != null
                            ? cpuStats.getCpuUsage().getPercpuUsage().size() : 1;
                    if (sysDelta > 0) cpuPercent = (double) cpuDelta / sysDelta * numCpus * 100.0;
                }

                // Memory calculation
                var memStats = stats.getMemoryStats();
                if (memStats != null) {
                    memUsed = memStats.getUsage() != null ? memStats.getUsage() : 0;
                    memLimit = memStats.getLimit() != null && memStats.getLimit() > 0
                            ? memStats.getLimit() : 1;
                    memoryPercent = (double) memUsed / memLimit * 100.0;
                }

                // Network I/O
                if (stats.getNetworks() != null) {
                    for (var net : stats.getNetworks().values()) {
                        rxBytes += net.getRxBytes();
                        txBytes += net.getTxBytes();
                    }
                }
            }

            return ContainerStats.builder()
                    .containerId(containerId)
                    .containerName(inspect.getName())
                    .cpuPercent(cpuPercent)
                    .memoryPercent(memoryPercent)
                    .memoryUsed(memUsed)
                    .memoryLimit(memLimit)
                    .status(status)
                    .networkRxBytes(rxBytes)
                    .networkTxBytes(txBytes)
                    .timestamp(System.currentTimeMillis())
                    .recentLogs(getBufferedLogs(containerId))
                    .build();

        } catch (Exception e) {
            log.error("Error getting stats for {}: {}", shortId(containerId), e.getMessage());
            return null;
        }
    }

    // ✅ FIX 4: Always query Docker for current replica count
    public int getCurrentReplicas() {
        return listContainers().size();  // No cache, always fresh
    }

    // ✅ FIX 5: Remove setCurrentReplicas - never needed
    // public void setCurrentReplicas(int replicas) { }  // DELETE THIS

    public boolean scaleUp(int count) {
        // ✅ Always get fresh count from Docker
        int current = getCurrentReplicas();
        int maxContainers = props.getDocker().getMaxContainers();
        int newCount = Math.min(current + count, maxContainers);

        if (newCount <= current) {
            log.warn("Already at max containers ({})", maxContainers);
            return false;
        }

        try {
            for (int i = current; i < newCount; i++) {
                String name = props.getDocker().getContainerPrefix() + "-" + System.currentTimeMillis();
                var response = dockerClient.createContainerCmd(props.getDocker().getImageName())
                        .withName(name)
                        .withEnv("SERVER_PORT=8080")
                        .exec();

                dockerClient.startContainerCmd(response.getId()).exec();
                log.info("✅ Scaled UP — started {}", name);
                startLogStream(response.getId());
            }
            return true;
        } catch (Exception e) {
            log.error("Error scaling up: {}", e.getMessage());
            return false;
        }
    }

    public boolean scaleDown(int count) {
        // ✅ Always get fresh count from Docker
        int current = getCurrentReplicas();
        int minContainers = props.getDocker().getMinContainers();
        int newCount = Math.max(current - count, minContainers);

        if (newCount >= current) {
            log.warn("Already at min containers ({})", minContainers);
            return false;
        }

        try {
            var containers = listContainers();
            int toRemove = current - newCount;

            for (int i = 0; i < toRemove && i < containers.size(); i++) {
                String id = containers.get(i).getId();
                String shortId = shortId(id);
                String name = containers.get(i).getNames()[0];

                // ✅ Stop and remove container
                dockerClient.stopContainerCmd(id).withTimeout(15).exec();
                dockerClient.removeContainerCmd(id).exec();

                // ✅ Clean up resources
                ResultCallback.Adapter<Frame> callback = logCallbacks.remove(shortId);
                if (callback != null) {
                    try { callback.close(); } catch (Exception ignored) {}
                }
                logBuffers.remove(shortId);

                log.info("✅ Scaled DOWN — removed {}", name);
            }
            return true;
        } catch (Exception e) {
            log.error("Error scaling down: {}", e.getMessage());
            return false;
        }
    }

    public boolean restartContainer(String containerId) {
        String shortId = shortId(containerId);
        try {
            dockerClient.restartContainerCmd(containerId).exec();
            log.info("🔄 Restarted container {}", shortId);

            // ✅ Restart log stream
            ResultCallback.Adapter<Frame> oldCallback = logCallbacks.remove(shortId);
            if (oldCallback != null) {
                try { oldCallback.close(); } catch (Exception ignored) {}
            }
            logBuffers.remove(shortId);
            startLogStream(containerId);

            return true;
        } catch (Exception e) {
            log.error("Error restarting container: {}", e.getMessage());
            return false;
        }
    }

    private String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : id;
    }
}