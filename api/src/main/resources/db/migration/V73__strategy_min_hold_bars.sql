-- 잔익절 방어 B안 — 최소 보유 캔들(minHoldBars) add-only 컬럼.
-- 진입 체결 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절 라인은 항상 작동 — 안전망 우선).
-- null = 비활성(현행 — DSL SELL 즉시 평가) → 기존 전략 0 변경(하위호환 LOCK).
--
-- 참고: 본 작업 task는 "RiskPlan JSONB 필드 add-only(Flyway 불요)"를 전제했으나, 실제 스키마는
-- risk plan 단일 JSONB blob이 아니라 take_profit_pct·fixed_stop_loss_pct 등 평면 컬럼 + scale_in_config·
-- partial_exits·entry_policy 각각의 전용 JSONB 컬럼 구조다. minHoldBars는 의미상 exit-noise 가드(평면 INT)이며,
-- 평면 risk 필드 패턴(take_profit_pct 1:1)을 따라 전용 컬럼으로 추가하는 것이 가장 정합적이고 단순(add-only nullable)이다.
ALTER TABLE strategy
    ADD COLUMN min_hold_bars integer;

COMMENT ON COLUMN strategy.min_hold_bars IS
    '잔익절 방어 B안 — 진입 체결 후 N캔들 동안 DSL 매도 시그널만 무시(손절·익절은 항상 작동). null=비활성(현행).';
