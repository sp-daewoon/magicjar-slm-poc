-- V12 — backtest_run (ADR 0061 §3 결정: 3 테이블 분리 B-1 채택)
-- 백테스트 실행 결과 메타 + KPI 저장 테이블
-- 사용자 결정 2026-05-12: A-1(본 사이클 R2 영속) · B-1(3 테이블) · C-1(5 endpoint) 채택

CREATE TABLE backtest_run (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(40)     NOT NULL,
    code            VARCHAR(8)      NOT NULL,
    interval        VARCHAR(8)      NOT NULL,
    rule_dsl        TEXT            NOT NULL,
    range_from      TIMESTAMPTZ     NOT NULL,
    range_to        TIMESTAMPTZ     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- PENDING/RUNNING/SUCCEEDED/FAILED
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    total_return    NUMERIC(15, 4),
    win_rate        NUMERIC(7, 4),
    mdd             NUMERIC(7, 4),
    sharpe          NUMERIC(7, 4),
    sortino         NUMERIC(7, 4),
    trade_count     INT,
    position_count  INT,
    detail_json     JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_backtest_run_user_code ON backtest_run (user_id, code, created_at DESC);
