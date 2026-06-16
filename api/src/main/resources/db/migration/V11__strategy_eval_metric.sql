-- V11: strategy_eval_metric 신규 테이블 (ADR 0047 §2-6 정합)
-- spec §6-5 V27 내용이나 실제 순번은 V11 (현행 max=V10 기준)

CREATE TABLE strategy_eval_metric (
    id              BIGSERIAL PRIMARY KEY,
    strategy_id     BIGINT        NOT NULL REFERENCES strategy (id) ON DELETE CASCADE,
    evaluated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    eval_time_ms    INTEGER       NOT NULL,
    cpu_time_ms     INTEGER,
    outcome         VARCHAR(32)   NOT NULL,  -- OK·TIMEOUT·CPU_EXCEEDED·UNKNOWN_SYMBOL·SYNTAX_ERROR
    error_msg       TEXT
);

CREATE INDEX idx_strategy_eval_metric_strategy_id_evaluated_at
    ON strategy_eval_metric (strategy_id, evaluated_at DESC);

COMMENT ON TABLE strategy_eval_metric IS 'ADR 0047 §2-6 — 마지막 N=100건 ring buffer (별도 cron retention)';
COMMENT ON COLUMN strategy_eval_metric.outcome IS 'OK·TIMEOUT·CPU_EXCEEDED·UNKNOWN_SYMBOL·SYNTAX_ERROR';
