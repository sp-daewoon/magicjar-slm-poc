-- V25__reconcile_run.sql
-- T-RECON Phase 2 (ADR 0041·0070 §3-1 + spec §2-3)
-- IF NOT EXISTS LOCK (헌법 §6 + ADR 0068 §15-1 hotfix 회피 패턴)

CREATE TABLE IF NOT EXISTS reconcile_run (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT        NOT NULL,
    started_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    drift_count   INT           NOT NULL DEFAULT 0,
    status        VARCHAR(16)   NOT NULL,           -- RUNNING · RECONCILED · DRIFT · DRIFT_PRICE · ERROR
    last_error    TEXT,
    trigger_source VARCHAR(16)  NOT NULL            -- CRON · MANUAL · API
);

CREATE INDEX IF NOT EXISTS ix_reconcile_run_user_started
    ON reconcile_run (user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_reconcile_run_status_started
    ON reconcile_run (status, started_at DESC);
