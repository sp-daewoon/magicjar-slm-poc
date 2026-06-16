-- V61: risk auto-pause를 전체 DISABLED가 아닌 BUY_BLOCKED 상태로 분리.
-- 신규 BUY는 차단하지만 SELL/익절/손절 평가는 유지해 기존 포지션 방어를 끊지 않는다.

ALTER TABLE strategy
    ALTER COLUMN status TYPE VARCHAR(16);

ALTER TABLE strategy
    DROP CONSTRAINT IF EXISTS chk_strategy_status;

ALTER TABLE strategy
    ADD CONSTRAINT chk_strategy_status
        CHECK (status IN ('ACTIVE', 'BUY_BLOCKED', 'PAUSED', 'DISABLED', 'DELETED'));

UPDATE strategy
SET status = 'BUY_BLOCKED',
    disabled_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'DISABLED'
  AND disabled_reason = 'RISK_VIOLATION';

CREATE INDEX IF NOT EXISTS idx_strategy_status_evaluable
    ON strategy (status)
    WHERE status IN ('ACTIVE', 'BUY_BLOCKED');

COMMENT ON COLUMN strategy.status
    IS 'ACTIVE·BUY_BLOCKED·PAUSED·DISABLED·DELETED. BUY_BLOCKED는 risk auto-pause 후 신규 BUY만 차단하고 SELL/청산 평가는 유지';
