-- ══════════════════════════════════════════════════════════════
--  Auto-Scaler Database Schema
--  Postgres 16 + pgvector
-- ══════════════════════════════════════════════════════════════

-- pgvector extension (ships in pgvector/pgvector:pg16 image)
CREATE EXTENSION IF NOT EXISTS vector;

-- ── 1. Scaling history (written by analytics-worker) ──────────
CREATE TABLE IF NOT EXISTS scaling_history (
                                               id          BIGSERIAL PRIMARY KEY,
                                               container   TEXT        NOT NULL,
                                               action      TEXT        NOT NULL,
                                               reason      TEXT,
                                               severity    TEXT,
                                               summary     TEXT,
                                               fix_plan    TEXT,
                                               created_at  TIMESTAMPTZ DEFAULT NOW()
    );

-- ── 2. Hourly metrics roll-up (written by analytics-worker) ───
CREATE TABLE IF NOT EXISTS metrics_hourly (
                                              id           BIGSERIAL PRIMARY KEY,
                                              container    TEXT        NOT NULL,
                                              hour_bucket  TIMESTAMPTZ NOT NULL,
                                              avg_cpu      NUMERIC(5,2),
    avg_memory   NUMERIC(5,2),
    max_cpu      NUMERIC(5,2),
    max_memory   NUMERIC(5,2),
    sample_count INT         DEFAULT 0,
    created_at   TIMESTAMPTZ DEFAULT NOW(),

    -- ✅ UNIQUE constraint so ON CONFLICT upsert works correctly
    CONSTRAINT uq_metrics_hourly_container_hour
    UNIQUE (container, hour_bucket)
    );

-- ── 3. pgvector RAG: metric snapshots ─────────────────────────
--    Each row = one ContainerStats JSON + its 768-dim embedding
CREATE TABLE IF NOT EXISTS metric_snapshots (
                                                id          BIGSERIAL PRIMARY KEY,
                                                container   TEXT        NOT NULL,
                                                snapshot    TEXT        NOT NULL,
                                                embedding   vector(768) NOT NULL,               -- ✅ fixed: 768 dims for jina-embeddings-v2-base-en
    created_at  TIMESTAMPTZ DEFAULT NOW()
    );

-- ── 4. pgvector RAG: scaling decisions ────────────────────────
--    Each row = one ScalingDecision JSON + its 768-dim embedding
CREATE TABLE IF NOT EXISTS scaling_events_vec (
                                                  id          BIGSERIAL PRIMARY KEY,
                                                  container   TEXT        NOT NULL,
                                                  event       TEXT        NOT NULL,
                                                  embedding    vector(768) NOT NULL,               -- ✅ fixed: 768 dims for jina-embeddings-v2-base-en
    created_at  TIMESTAMPTZ DEFAULT NOW()
    );


-- ── 5. Worker job audit log (written by remediation-worker) ───
CREATE TABLE IF NOT EXISTS worker_jobs (
                                           id            BIGSERIAL PRIMARY KEY,
                                           job_type      VARCHAR(100),
    topic         VARCHAR(255),
    payload       TEXT,
    status        VARCHAR(50),
    worker_name   VARCHAR(100),
    error_message TEXT,
    created_at    TIMESTAMPTZ DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_worker_jobs_worker
    ON worker_jobs (worker_name, created_at DESC);
-- ── Indexes ───────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_scaling_history_container
    ON scaling_history (container, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_metrics_hourly_container
    ON metrics_hourly (container, hour_bucket DESC);

-- IVFFlat ANN indexes for cosine similarity search
CREATE INDEX IF NOT EXISTS idx_snapshots_embedding
    ON metric_snapshots USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 50);

CREATE INDEX IF NOT EXISTS idx_events_embedding
    ON scaling_events_vec USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 50);

CREATE INDEX IF NOT EXISTS idx_snapshots_container
    ON metric_snapshots (container, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_events_container
    ON scaling_events_vec (container, created_at DESC);