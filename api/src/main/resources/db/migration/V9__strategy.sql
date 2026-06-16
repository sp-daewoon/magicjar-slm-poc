-- V9: strategy 기본 테이블 신설 — T-DSL W2 진입
-- ADR 0022 §2-1 KIS 호환 DSL 전략 영속 + ADR 0047 §2-6 정합.

CREATE TABLE strategy (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT        NOT NULL,
    name                VARCHAR(200)  NOT NULL,
    name_ko             VARCHAR(200),
    dsl_entry           TEXT          NOT NULL,
    dsl_exit            TEXT,
    universe_market     VARCHAR(20)   NOT NULL DEFAULT 'KOSPI',
    tf                  VARCHAR(10)   NOT NULL DEFAULT 'D1',
    ast_depth           INTEGER       NOT NULL DEFAULT 0,
    node_count          INTEGER       NOT NULL DEFAULT 0,
    indicator_call_count INTEGER      NOT NULL DEFAULT 0,
    estimated_eval_time_ms INTEGER    NOT NULL DEFAULT 0,
    complexity_class    VARCHAR(20)   NOT NULL DEFAULT 'LOW',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_strategy_user_id ON strategy (user_id);
CREATE INDEX idx_strategy_created_at ON strategy (created_at DESC);

COMMENT ON TABLE strategy IS 'T-DSL W2 — KIS 호환 DSL 전략 정의 (ADR 0022 §2-1 + ADR 0047 §2-6)';
COMMENT ON COLUMN strategy.dsl_entry IS 'KIS 텍스트 형식 매수 조건 (1줄 형식: ma(5) crosses_above ma(20))';
COMMENT ON COLUMN strategy.dsl_exit  IS 'KIS 텍스트 형식 매도 조건';
COMMENT ON COLUMN strategy.complexity_class IS 'ADR 0047 §2-1-B — LOW·MEDIUM·HIGH·CRITICAL';
