-- V15: signal 테이블 신규 — T-SIG Phase 2 (ADR 0066 §5-2 + §2-5 E-1)
-- UQ (strategy_id, code, interval, ts) 멱등 보장

CREATE TABLE signal (
    id              BIGSERIAL       PRIMARY KEY,
    strategy_id     BIGINT          NOT NULL REFERENCES strategy (id) ON DELETE CASCADE,
    user_id         VARCHAR(40)     NOT NULL,
    code            VARCHAR(8)      NOT NULL,
    interval        VARCHAR(8)      NOT NULL,
    ts              TIMESTAMPTZ     NOT NULL,
    side            VARCHAR(4)      NOT NULL
        CONSTRAINT chk_signal_side CHECK (side IN ('BUY', 'SELL', 'HOLD')),
    strength        INTEGER         NOT NULL DEFAULT 0,
    reason          VARCHAR(500),
    price           NUMERIC(19, 4),
    rule_signal     VARCHAR(80),
    context_json    JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_signal_strategy_code_interval_ts
        UNIQUE (strategy_id, code, interval, ts)
);

-- 사용자 시계열 페이징 조회 (SignalController GET /api/signals)
CREATE INDEX idx_signal_user_ts   ON signal (user_id, ts DESC);
-- 종목별 시계열 조회
CREATE INDEX idx_signal_code_ts   ON signal (code, ts DESC);
-- 전략별 조회
CREATE INDEX idx_signal_strategy  ON signal (strategy_id, ts DESC);

COMMENT ON TABLE signal IS 'T-SIG Phase 2 — 룰 기반 시그널 영속 (ADR 0066 §5-2, UQ 멱등 E-1)';
COMMENT ON COLUMN signal.strategy_id  IS '시그널 발행 전략 ID';
COMMENT ON COLUMN signal.user_id      IS '전략 소유자 userId (denormalized — 페이징 조회 효율)';
COMMENT ON COLUMN signal.code         IS '종목 코드 (KIS 6자리)';
COMMENT ON COLUMN signal.interval     IS '평가 인터벌 code (5m·15m·30m·90m·1h)';
COMMENT ON COLUMN signal.ts           IS 'boundary closing ts (deterministic — ADR 0064 §4-2)';
COMMENT ON COLUMN signal.side         IS 'BUY·SELL·HOLD';
COMMENT ON COLUMN signal.strength     IS '룰 매칭 강도 0~100 (ADR 0023 §2-1)';
COMMENT ON COLUMN signal.reason       IS '룰 매칭 사유 요약';
COMMENT ON COLUMN signal.price        IS '평가 시점 종가 (boundary close raw)';
COMMENT ON COLUMN signal.rule_signal  IS '매칭된 룰 식별자 (선택)';
COMMENT ON COLUMN signal.context_json IS '평가 context JSON (indicator values · pattern matched)';
