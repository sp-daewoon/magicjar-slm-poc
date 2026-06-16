-- V28__screening_run_and_match.sql
-- T-SC Phase 2 옵션 영속 (ADR 0071 §3 D-2)
-- 기본 비활성(MAGICJAR_SCREENER_PERSIST=false) — env toggle 시 ON
-- IF NOT EXISTS 강제 LOCK (헌법 §6 + ADR 0068·0070 hotfix 회피 패턴)

CREATE TABLE IF NOT EXISTS screening_run (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    strategy_id BIGINT NOT NULL,
    universe_filter_hash VARCHAR(64) NOT NULL,
    dsl_hash VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    universe_size INT NOT NULL DEFAULT 0,
    evaluated_count INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,        -- RUNNING · COMPLETED · ERROR
    last_error TEXT,
    trigger_source VARCHAR(16) NOT NULL -- API · CRON · MANUAL
);

CREATE INDEX IF NOT EXISTS ix_screening_run_user_started
    ON screening_run (user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_screening_run_strategy_started
    ON screening_run (strategy_id, started_at DESC);

CREATE TABLE IF NOT EXISTS screening_match (
    id BIGSERIAL PRIMARY KEY,
    screening_run_id BIGINT NOT NULL REFERENCES screening_run(id) ON DELETE CASCADE,
    instrument_code VARCHAR(16) NOT NULL,
    rank INT NOT NULL,
    score NUMERIC(10, 6) NOT NULL,
    score_components JSONB NOT NULL,  -- {"signal": 0.45, "volume": 0.20, "atr": 0.18, "cap": 0.08}
    reason TEXT
);

CREATE INDEX IF NOT EXISTS ix_screening_match_run_rank
    ON screening_match (screening_run_id, rank);

CREATE INDEX IF NOT EXISTS ix_screening_match_code
    ON screening_match (instrument_code);
