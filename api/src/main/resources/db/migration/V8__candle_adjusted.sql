-- =============================================================================
-- V8 — candle 테이블 adjustment_factor + volume_adjusted 컬럼 추가
-- (T-CA1 PR-29b market-data 책임)
-- ADR 0018 §2-2 정합
-- 작성: market-data (PR-29b)
-- =============================================================================
-- V1 baseline candle 테이블 기준:
--   기존: open_raw, high_raw, low_raw, close_raw (NUMERIC 19,4)
--         open_adj, high_adj, low_adj, close_adj  (NUMERIC 19,4)
--         volume (BIGINT)
--
-- V8 add-only (기존 컬럼 변경 0):
--   adjustment_factor : 누적 보정 계수 (default 1.0 — 미보정)
--   volume_adjusted   : 분할 보정 거래량 (null = 미보정)
--
-- CandleAdjustmentJob 보정 로직 (ADR 0018 §4-3):
--   액면분할 N:1 → open_adj/high_adj/low_adj/close_adj *= (1/N)
--                 volume_adjusted = volume * N
--                 adjustment_factor *= (1/N)
--   보정 누적: 다단계 분할 시 adjustment_factor 곱 누적
-- =============================================================================

ALTER TABLE candle
    ADD COLUMN IF NOT EXISTS adjustment_factor NUMERIC(15, 8) DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS volume_adjusted   BIGINT;

-- 기존 candle 행: adjustment_factor = 1.0 (미보정 default — WHERE NULL 조건으로 멱등)
UPDATE candle
SET adjustment_factor = 1.0
WHERE adjustment_factor IS NULL;

-- 복합 인덱스 (CandleAdjustmentJob SELECT WHERE code + ts < ex_date 최적화)
CREATE INDEX IF NOT EXISTS idx_candle_code_interval_ts_v8
    ON candle (code, interval, ts DESC);
