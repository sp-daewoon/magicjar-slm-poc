-- V65: strategy scale-in / partial-exit 정책 컬럼 add-only (ADR 0111, 분할매수 R1)
--
-- spec 2026-06-05-scale-in-exit-ownership-spec.md §4 (본문 V66 → orchestrator 확정 V65로 당김).
--
-- add-only: 기존 strategy 행은 두 JSONB 모두 NULL → scale-in 없는 단일 진입 + 전량 익절(현행) 동작.
-- 기존 fixed_stop_loss_pct(V60)·take_profit_pct(V59)는 유지. rollback 시 컬럼 사용만 제거.

ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS scale_in_config JSONB,
    ADD COLUMN IF NOT EXISTS partial_exits   JSONB;

COMMENT ON COLUMN strategy.scale_in_config IS 'ADR 0111 — ScaleInPlan 직렬화(분할 회수·회당 비중·추가 진입 트리거). NULL이면 단일 진입(현행 하위호환).';
COMMENT ON COLUMN strategy.partial_exits IS 'ADR 0111 — PartialExit 리스트 직렬화(분할 익절 단계: triggerProfitPct·exitWeightPct). NULL이면 전량 익절(현행 하위호환).';
