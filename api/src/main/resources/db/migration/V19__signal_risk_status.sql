-- V19: signal.risk_status 컬럼 추가 — T-RISK Phase 2 (ADR 0067 §5-5 F-1)
-- SignalRiskStatusUpdater port 구현 기반 — PENDING→ALLOWED/BLOCKED

ALTER TABLE signal
    ADD COLUMN risk_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_signal_risk_status CHECK (risk_status IN ('PENDING', 'ALLOWED', 'BLOCKED'));

CREATE INDEX idx_signal_risk_status ON signal(risk_status, ts DESC);

COMMENT ON COLUMN signal.risk_status IS 'Risk 평가 상태 — PENDING(미평가)·ALLOWED(통과)·BLOCKED(위반). ADR 0067 §5-5 F-1';
