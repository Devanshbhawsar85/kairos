package com.autoscaler.service;

import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final JdbcTemplate    jdbc;
    private final EmbeddingService embedder;
    // ✅ FIX 6: Inject DataSource directly so we can register PGvector
    // on ALL connections in the HikariCP pool, not just one random connection.
    private final DataSource       dataSource;

    @Value("${autoscaler.vector-store.snapshot-retention:500}")
    private int snapshotRetention;

    @Value("${autoscaler.vector-store.event-retention:200}")
    private int eventRetention;

    // ── Bootstrap ────────────────────────────────────────────────

    /**
     * ✅ FIX 6: Register PGvector type on the DataSource connection factory
     * level so every connection HikariCP creates — now and in the future —
     * has the vector type registered. The old approach of registering on a
     * single JdbcTemplate connection missed pooled connections.
     */
    @PostConstruct
    public void registerVectorType() {
        try {
            // Register on the datasource itself — affects ALL pool connections
            try (Connection con = dataSource.getConnection()) {
                PGvector.addVectorType(con);
            }
            // Also register via JdbcTemplate for the current connection
            jdbc.execute((Connection con) -> {
                PGvector.addVectorType(con);
                return null;
            });
            log.info("✅ PGvector type registered with JDBC connection pool");
        } catch (Exception e) {
            log.error("Failed to register PGvector type: {}", e.getMessage());
        }
    }

    // ── WRITE ────────────────────────────────────────────────────

    public void saveSnapshot(String container, String statsJson) {
        try {
            float[] vec = embedder.embed(statsJson);
            // ✅ FIX: log zero-vector warning so you know if Jina is down
            if (isZeroVector(vec)) {
                log.warn("⚠️  Zero embedding for snapshot of {} — Jina AI may be down or key missing", container);
            }
            jdbc.update(
                    "INSERT INTO metric_snapshots (container, snapshot, embedding) VALUES (?, ?, ?::vector)",
                    container, statsJson, new PGvector(vec)
            );
            log.debug("📥 Snapshot saved for container={}", container);
        } catch (Exception e) {
            log.error("Failed to save snapshot for {}: {}", container, e.getMessage());
        }
    }

    public void saveScalingEvent(String container, String decisionJson) {
        try {
            float[] vec = embedder.embed(decisionJson);
            if (isZeroVector(vec)) {
                log.warn("⚠️  Zero embedding for scaling event of {} — Jina AI may be down or key missing", container);
            }
            jdbc.update(
                    "INSERT INTO scaling_events_vec (container, event, embedding) VALUES (?, ?, ?::vector)",
                    container, decisionJson, new PGvector(vec)
            );
            log.debug("📥 Scaling event saved for container={}", container);
        } catch (Exception e) {
            log.error("Failed to save scaling event for {}: {}", container, e.getMessage());
        }
    }

    // ── READ ─────────────────────────────────────────────────────

    public String getSimilarSnapshots(String container, String queryText, int topK) {
        try {
            float[] qVec = embedder.embed(queryText);
            // ✅ FIX: If Jina is down, fall back to recency-ordered results
            // instead of returning useless cosine distances on zero vectors
            String sql;
            Object[] params;
            if (isZeroVector(qVec)) {
                log.warn("⚠️  Zero query vector for snapshots — falling back to recency order");
                sql = """
                    SELECT snapshot FROM metric_snapshots
                    WHERE container = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """;
                params = new Object[]{container, topK};
            } else {
                sql = """
                    SELECT snapshot FROM metric_snapshots
                    WHERE container = ?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """;
                params = new Object[]{container, new PGvector(qVec), topK};
            }

            List<String> rows = jdbc.queryForList(sql, String.class, params);
            if (rows.isEmpty()) return "{}";
            log.debug("🔍 Retrieved {} similar snapshots for {}", rows.size(), container);
            return "[" + String.join(",", rows) + "]";
        } catch (Exception e) {
            log.error("getSimilarSnapshots failed for {}: {}", container, e.getMessage());
            return "{}";
        }
    }

    public String getSimilarEvents(String container, String queryText, int topK) {
        try {
            float[] qVec = embedder.embed(queryText);
            String sql;
            Object[] params;
            if (isZeroVector(qVec)) {
                log.warn("⚠️  Zero query vector for events — falling back to recency order");
                sql = """
                    SELECT event FROM scaling_events_vec
                    WHERE container = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """;
                params = new Object[]{container, topK};
            } else {
                sql = """
                    SELECT event FROM scaling_events_vec
                    WHERE container = ?
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """;
                params = new Object[]{container, new PGvector(qVec), topK};
            }

            List<String> rows = jdbc.queryForList(sql, String.class, params);
            if (rows.isEmpty()) return "{}";
            log.debug("🔍 Retrieved {} similar events for {}", rows.size(), container);
            return "[" + String.join(",", rows) + "]";
        } catch (Exception e) {
            log.error("getSimilarEvents failed for {}: {}", container, e.getMessage());
            return "{}";
        }
    }

    // ── PRUNE ────────────────────────────────────────────────────

    public void pruneOldData(String container) {
        try {
            int deletedSnaps = jdbc.update("""
                DELETE FROM metric_snapshots
                WHERE container = ?
                  AND id NOT IN (
                      SELECT id FROM metric_snapshots
                      WHERE container = ?
                      ORDER BY created_at DESC
                      LIMIT ?
                  )
                """, container, container, snapshotRetention);

            int deletedEvents = jdbc.update("""
                DELETE FROM scaling_events_vec
                WHERE container = ?
                  AND id NOT IN (
                      SELECT id FROM scaling_events_vec
                      WHERE container = ?
                      ORDER BY created_at DESC
                      LIMIT ?
                  )
                """, container, container, eventRetention);

            if (deletedSnaps > 0 || deletedEvents > 0) {
                log.info("🧹 Pruned {} snapshots + {} events for {}",
                        deletedSnaps, deletedEvents, container);
            }
        } catch (Exception e) {
            log.error("pruneOldData failed for {}: {}", container, e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * ✅ Detects zero vectors (Jina API missing/failed).
     * Cosine similarity on zero vectors is mathematically undefined
     * and returns garbage results from pgvector.
     */
    private boolean isZeroVector(float[] vec) {
        if (vec == null || vec.length == 0) return true;
        for (float v : vec) {
            if (v != 0.0f) return false;
        }
        return true;
    }
}