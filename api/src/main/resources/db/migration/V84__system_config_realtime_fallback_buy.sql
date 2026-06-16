-- ACTIVE 전략 degraded/fallback candle BUY 허용 정책.
-- 기본 TRUE: 기존 동작 보존. 설정 화면에서 OFF 시 fallback source candle은 신규 BUY만 차단하고 SELL 평가는 유지한다.
ALTER TABLE system_config
    ADD COLUMN IF NOT EXISTS realtime_fallback_buy_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN system_config.realtime_fallback_buy_enabled IS
    'TRUE면 KIS-POLLING/KIS-NOTICK-FALLBACK 등 fallback source candle에서도 신규 BUY 시그널 허용. FALSE면 BUY만 suppress, SELL은 진행.';
