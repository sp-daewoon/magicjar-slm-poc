-- V10: strategy 테이블 — auto-disable 컬럼 추가 (ADR 0047 §2-2-D + §2-6 정합)
-- spec §6-5 V26 내용이나 실제 순번은 V10 (현행 max=V9 기준)

ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS disabled_reason   VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_disabled_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS eval_metric_summary JSONB;

COMMENT ON COLUMN strategy.disabled_reason     IS 'NULL·TIMEOUT·CPU_BUDGET_EXCEEDED·MANUAL — ADR 0047 §2-2-D';
COMMENT ON COLUMN strategy.last_disabled_at    IS 'auto-disable 발생 시각';
COMMENT ON COLUMN strategy.eval_metric_summary IS 'last N evaluations summary (avg ms, timeout count, cpu_violations) — ring buffer summary';
