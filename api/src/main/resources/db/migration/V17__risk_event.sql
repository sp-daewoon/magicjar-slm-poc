-- V17: risk_event 테이블 신규 — T-RISK Phase 2 (ADR 0067 §5-2 G-1)
-- Risk 위반 이벤트 영속 — BLOCKED만 저장 권장 (§9-1)

CREATE TABLE risk_event (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     VARCHAR(40)     NOT NULL,
    signal_id   BIGINT          REFERENCES signal(id) ON DELETE CASCADE,
    rule        VARCHAR(40)     NOT NULL,
    verdict     VARCHAR(20)     NOT NULL,
    reason      VARCHAR(500),
    ts          TIMESTAMPTZ     NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_risk_event_user_ts  ON risk_event(user_id, ts DESC);
CREATE INDEX idx_risk_event_signal   ON risk_event(signal_id);

COMMENT ON TABLE risk_event IS 'T-RISK Phase 2 — Risk 위반 이벤트 영속 (ADR 0067 §5-2 G-1, BLOCKED 권장)';
COMMENT ON COLUMN risk_event.user_id    IS '사용자 ID';
COMMENT ON COLUMN risk_event.signal_id  IS '시그널 ID (signal FK — ON DELETE CASCADE)';
COMMENT ON COLUMN risk_event.rule       IS 'RiskRule enum (POSITION_LIMIT·DAILY_LOSS_LIMIT·MAX_CONCURRENT·TRADE_FREQ_MINUTE·TRADE_FREQ_DAY·KILL_SWITCH_ON·USER_DISABLED)';
COMMENT ON COLUMN risk_event.verdict    IS 'ALLOWED·BLOCKED';
COMMENT ON COLUMN risk_event.ts         IS 'risk 평가 시점 ts';
