-- V39: candle.backfill_batch_id add-only (ADR 0086 §2-1 룰 #3 Rollback 안전망)
-- 본 컬럼은 T-CC Phase 2-A CandleBackfillWorker 의 backfill 변경 추적 용도.
-- 형식: candle-backfill:CODE:INTERVAL:EPOCH_MS-UUID (예: candle-backfill:004090:1d:1735603200000-3a7b...)
-- 인덱스 0 — 운영 query 영향 0 (audit 추적·운영자 수동 rollback 용도만).
-- 1차 MVP rollback path: `DELETE FROM candle WHERE backfill_batch_id = ?` (SQL 수동 가능).

ALTER TABLE candle
    ADD COLUMN IF NOT EXISTS backfill_batch_id VARCHAR(96) NULL;

COMMENT ON COLUMN candle.backfill_batch_id IS
    'CandleBackfillWorker batch 추적 ID (ADR 0086 §2-1 룰 #3) — null=수동·코어 적재, "candle-backfill:..."=Phase 2-A 자동 backfill.';
