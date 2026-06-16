-- 전략별 VI 발동 행동 정책.
-- 기본값 ALERT_ONLY: 기존 전략은 주문 없이 VI 관측/알림만 허용한다.
ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS vi_action_policy varchar(24) NOT NULL DEFAULT 'ALERT_ONLY';

COMMENT ON COLUMN strategy.vi_action_policy IS
    'VI action policy: EXIT_MARKET sends MARKET SELL, ALERT_ONLY observes/notifies, HOLD does not subscribe or trade.';
