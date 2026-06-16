-- ============================================================================
-- V85 — strategy_daily_performance (전략×일자 성과 누적 원장)
-- ============================================================================
-- spec: _workspace/specs/2026-06-16-strategy-daily-performance.md §3 (V85)
-- 목적: 전략별로 매일 청산된 왕복(round-trip) 매매 성과를 영속 누적한다.
--       실현손익·승률·수익률 + 그날 벤치마크 지수 등락률·초과수익 α·시장 레짐을
--       하나의 일자행으로 기록 → "장이 좋아서 번 건지, 전략이 좋아서 번 건지" 분리.
-- publisher: batch DailyPerformanceJob (매일 00:30 KST T-1 집계 · 멱등 UPSERT) + 1회성 백필.
-- 거래 단위: 1 거래 = 1 왕복(진입→완전 청산). 귀속일 = 완전 청산된 close date (spec §2).
--
-- UQ (user_id, strategy_id, trade_date):
--   배치/백필 재실행 시 동일 (전략, 일자) 행을 멱등 UPSERT 하기 위한 비즈니스 키.
-- INDEX (strategy_id, trade_date DESC):
--   전략 성과 히스토리 조회(최신순 일자 목록) 액세스 패턴.
--
-- 컨벤션: IF NOT EXISTS LOCK (헌법 §6 + ADR 0068·0070·0071) · BIGSERIAL PK ·
--         TIMESTAMPTZ DEFAULT now() · user_id VARCHAR(40) (pnl_daily V30 형제 정합) ·
--         strategy_id BIGINT (strategy.id V9 정합) · 읽기 전용 소스 집계라 FK 미부착
--         (pnl_daily V30·strategy_position 백필 멱등 패턴 정합 — 백필 시점 전략 삭제 내성).
-- ============================================================================
CREATE TABLE IF NOT EXISTS strategy_daily_performance (
    id                   BIGSERIAL       PRIMARY KEY,
    user_id              VARCHAR(40)     NOT NULL,
    strategy_id          BIGINT          NOT NULL,
    trade_date           DATE            NOT NULL,                  -- KST 영업일 (왕복 완전 청산일)
    round_trip_count     INT             NOT NULL DEFAULT 0,        -- 그날 청산된 왕복 수
    win_count            INT             NOT NULL DEFAULT 0,        -- 왕복 손익 > 0
    loss_count           INT             NOT NULL DEFAULT 0,        -- 왕복 손익 < 0
    realized_pnl         NUMERIC(20, 4)  NOT NULL DEFAULT 0,        -- 청산 왕복 손익 합 (원)
    total_cost_basis     NUMERIC(20, 4)  NOT NULL DEFAULT 0,        -- 청산 왕복 진입금액 합 (원) — 수익률 분모
    return_pct           NUMERIC(10, 4)  NULL,                      -- realized_pnl / total_cost_basis × 100 (cost_basis=0 이면 null)
    benchmark_index_code VARCHAR(16)     NULL,                      -- 전략 universe 기준 지수 (0001 코스피 / 1001 코스닥)
    benchmark_change_pct NUMERIC(10, 4)  NULL,                      -- 그날 벤치마크 지수 등락률 (snapshot 부재 시 null)
    alpha_pct            NUMERIC(10, 4)  NULL,                      -- return_pct − benchmark_change_pct (둘 중 null 이면 null)
    market_regime        VARCHAR(16)     NULL,                      -- UPTREND · DOWNTREND · SIDEWAYS · HIGH_VOL (지수 부재 시 null)
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- 배치/백필 멱등 UPSERT 비즈니스 키.
CREATE UNIQUE INDEX IF NOT EXISTS ux_strategy_daily_perf_user_strategy_date
    ON strategy_daily_performance (user_id, strategy_id, trade_date);

-- 전략 성과 히스토리 조회(최신 일자순).
CREATE INDEX IF NOT EXISTS ix_strategy_daily_perf_strategy_date_desc
    ON strategy_daily_performance (strategy_id, trade_date DESC);

COMMENT ON TABLE  strategy_daily_performance                      IS 'spec 2026-06-16 §3 — 전략×일자 성과 누적 원장. DailyPerformanceJob(00:30 KST)이 order_execution replay 로 왕복 집계 + 벤치마크·α·레짐 채워 멱등 UPSERT.';
COMMENT ON COLUMN strategy_daily_performance.trade_date           IS 'KST 영업일 = 왕복이 완전 청산된 close date (며칠 걸친 왕복은 청산일에 귀속).';
COMMENT ON COLUMN strategy_daily_performance.round_trip_count     IS '그날 청산된 왕복(round-trip) 수. 손익=0 왕복도 포함(win/loss 어디에도 안 들어감).';
COMMENT ON COLUMN strategy_daily_performance.realized_pnl         IS '청산 왕복 손익 합 (원). (매도가 − 평균단가) × 청산수량 의 누적.';
COMMENT ON COLUMN strategy_daily_performance.total_cost_basis     IS '청산 왕복 진입금액 합 (원) = 평균단가 × 청산수량 누적. 수익률 분모.';
COMMENT ON COLUMN strategy_daily_performance.return_pct           IS 'realized_pnl / total_cost_basis × 100. total_cost_basis=0 이면 null.';
COMMENT ON COLUMN strategy_daily_performance.benchmark_index_code IS '전략 universe 기준 지수 코드 (0001=코스피 · 1001=코스닥). 혼합/불명은 0001 기본.';
COMMENT ON COLUMN strategy_daily_performance.benchmark_change_pct IS '그날 벤치마크 지수 등락률 (market_index_daily.change_pct). 지수 snapshot 부재 시 null.';
COMMENT ON COLUMN strategy_daily_performance.alpha_pct            IS '초과수익 α = return_pct − benchmark_change_pct. 둘 중 하나라도 null 이면 null.';
COMMENT ON COLUMN strategy_daily_performance.market_regime        IS '그날 지수 기준 룰 레짐 (UPTREND·DOWNTREND·SIDEWAYS·HIGH_VOL). 지수 부재 시 null.';
