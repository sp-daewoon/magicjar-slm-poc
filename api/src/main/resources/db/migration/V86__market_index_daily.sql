-- ============================================================================
-- V86 — market_index_daily (지수×일자 OHLC 롤업 · 전 전략 공유)
-- ============================================================================
-- spec: _workspace/specs/2026-06-16-strategy-daily-performance.md §3 (V86) · §4-4 레짐
-- 목적: market_index_snapshot(V47, 1분 단위)을 그날치 OHLC·등락률·변동성·레짐으로
--       일자 롤업해 영속한다. strategy_daily_performance 의 벤치마크·α·레짐 source.
--       v1 대상 = 0001(코스피)·1001(코스닥) 2종이며 확장 가능.
-- publisher: batch DailyPerformanceJob (매일 00:30 KST 지수 일봉 롤업 · 멱등 UPSERT) + 백필.
-- 레짐 (spec §4-4 우선순위): range_pct ≥ 2.5% → HIGH_VOL · change_pct ≥ +0.6% → UPTREND ·
--                            change_pct ≤ −0.6% → DOWNTREND · else SIDEWAYS.
--
-- UQ (index_code, trade_date):
--   배치/백필 재실행 시 동일 (지수, 일자) 롤업을 멱등 UPSERT 하기 위한 비즈니스 키.
--
-- 컨벤션: IF NOT EXISTS LOCK (헌법 §6) · BIGSERIAL PK · TIMESTAMPTZ DEFAULT now() ·
--         OHLC NUMERIC(14,2) nullable (snapshot 부재일 대비) · index_code VARCHAR(16)
--         (market_index_snapshot.code 정합).
-- ============================================================================
CREATE TABLE IF NOT EXISTS market_index_daily (
    id           BIGSERIAL       PRIMARY KEY,
    index_code   VARCHAR(16)     NOT NULL,                  -- KIS index code (0001=코스피 · 1001=코스닥, v1 2종 확장 가능)
    trade_date   DATE            NOT NULL,                  -- KST 영업일
    open         NUMERIC(14, 2)  NULL,                      -- 그날 첫 snapshot 가격
    high         NUMERIC(14, 2)  NULL,                      -- 그날 max
    low          NUMERIC(14, 2)  NULL,                      -- 그날 min
    close        NUMERIC(14, 2)  NULL,                      -- 그날 마지막 snapshot 가격
    change_pct   NUMERIC(10, 4)  NULL,                      -- (close − prev_close)/prev_close × 100. 전일 없으면 (close−open)/open
    range_pct    NUMERIC(10, 4)  NULL,                      -- (high − low)/open × 100 — 변동성
    regime       VARCHAR(16)     NULL,                      -- 본 지수 기준 레짐 (UPTREND·DOWNTREND·SIDEWAYS·HIGH_VOL)
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 배치/백필 멱등 UPSERT 비즈니스 키.
CREATE UNIQUE INDEX IF NOT EXISTS ux_market_index_daily_code_date
    ON market_index_daily (index_code, trade_date);

-- 지수 일자 히스토리 조회(최신순).
CREATE INDEX IF NOT EXISTS ix_market_index_daily_code_date_desc
    ON market_index_daily (index_code, trade_date DESC);

COMMENT ON TABLE  market_index_daily            IS 'spec 2026-06-16 §3 — 지수×일자 OHLC 롤업(전 전략 공유). market_index_snapshot(V47, 1분)을 그날치로 집계.';
COMMENT ON COLUMN market_index_daily.index_code IS 'KIS index code (0001=코스피 · 1001=코스닥). v1 2종, 확장 가능.';
COMMENT ON COLUMN market_index_daily.change_pct IS '(close − prev_close)/prev_close × 100. 전일 행 없으면 (close − open)/open.';
COMMENT ON COLUMN market_index_daily.range_pct  IS '(high − low)/open × 100 — 당일 변동성. 레짐 HIGH_VOL 판정 입력.';
COMMENT ON COLUMN market_index_daily.regime     IS '본 지수 기준 룰 레짐 (spec §4-4 우선순위: HIGH_VOL > UPTREND > DOWNTREND > SIDEWAYS).';
