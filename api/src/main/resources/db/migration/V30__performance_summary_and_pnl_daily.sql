-- V30__performance_summary_and_pnl_daily.sql
-- T-PERF 영속 (ADR 0072 §2-3·§2-4 C-1·D-1)
-- batch DailyPerformanceJob 매일 00:30 KST cron 결과 영속
-- IF NOT EXISTS LOCK (헌법 §6 + ADR 0068·0070·0071 패턴 정합)
-- user_id: VARCHAR(40) — user_account.user_id 정합 (V1 baseline 참조)

-- ────────────────────────────────────────────────────────────────
-- pnl_daily: 일별 실현 손익 집계
-- ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pnl_daily (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40)     NOT NULL,
    trade_date      DATE            NOT NULL,
    realized_pnl    NUMERIC(18, 2)  NOT NULL DEFAULT 0,
    win_count       INT             NOT NULL DEFAULT 0,
    loss_count      INT             NOT NULL DEFAULT 0,
    trade_count     INT             NOT NULL DEFAULT 0,
    avg_profit      NUMERIC(18, 2),
    avg_loss        NUMERIC(18, 2),
    equity_at_close NUMERIC(18, 2),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_pnl_daily_user_date
    ON pnl_daily (user_id, trade_date);

CREATE INDEX IF NOT EXISTS ix_pnl_daily_user_date_desc
    ON pnl_daily (user_id, trade_date DESC);

-- ────────────────────────────────────────────────────────────────
-- performance_summary: 기간별 통계 요약 (DAY·WEEK·MONTH·YEAR·ALL)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS performance_summary (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(40)     NOT NULL,
    period              VARCHAR(8)      NOT NULL,    -- DAY · WEEK · MONTH · YEAR · ALL
    period_start        DATE            NOT NULL,
    period_end          DATE            NOT NULL,
    total_pnl           NUMERIC(18, 2)  NOT NULL,
    mdd_pct             NUMERIC(8, 4)   NOT NULL DEFAULT 0,
    win_rate_pct        NUMERIC(5, 2)   NOT NULL DEFAULT 0,
    profit_loss_ratio   NUMERIC(8, 4),
    trade_count         INT             NOT NULL DEFAULT 0,
    metrics             JSONB           NOT NULL DEFAULT '{}',  -- {"sharpe": null, "sortino": null, "cagr": null} placeholder
    computed_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_perf_summary_user_period
    ON performance_summary (user_id, period, period_start, period_end);

CREATE INDEX IF NOT EXISTS ix_perf_summary_user_period_desc
    ON performance_summary (user_id, period, period_end DESC);
