-- V14: strategy 테이블 확장 — status 4단 + codes/intervals JSONB (ADR 0066 §2-6 F-1)
-- Phase 2 T-SIG backend-core

ALTER TABLE strategy
    ADD COLUMN status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_strategy_status CHECK (status IN ('ACTIVE', 'PAUSED', 'DISABLED', 'DELETED')),
    ADD COLUMN codes        JSONB,
    ADD COLUMN intervals    JSONB,
    ADD COLUMN paused_at    TIMESTAMPTZ,
    ADD COLUMN disabled_at  TIMESTAMPTZ,
    ADD COLUMN deleted_at   TIMESTAMPTZ;

-- 기존 disabled_reason 기반 전략 status 동기 (disabled_reason IS NOT NULL → DISABLED)
UPDATE strategy
SET    status = 'DISABLED'
WHERE  disabled_reason IS NOT NULL;

-- 부분 인덱스: ACTIVE 전략만 — 실시간 시그널 평가 hot path (ADR 0066 §2-5)
CREATE INDEX idx_strategy_status_active
    ON strategy (status)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN strategy.status    IS 'ADR 0066 §2-6 F-1 — ACTIVE·PAUSED·DISABLED·DELETED 4단 상태';
COMMENT ON COLUMN strategy.codes     IS 'JSONB 문자열 배열 — 구독 종목 코드 리스트 (예: ["005930","000660"])';
COMMENT ON COLUMN strategy.intervals IS 'JSONB 문자열 배열 — 구독 인터벌 리스트 (예: ["5m","15m"])';
COMMENT ON COLUMN strategy.paused_at IS '사용자 PAUSED 전환 시각';
COMMENT ON COLUMN strategy.disabled_at IS 'DISABLED 전환 시각 (auto-disable 또는 사용자 명시)';
COMMENT ON COLUMN strategy.deleted_at IS 'soft delete 시각';
