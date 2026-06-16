-- ADR 0114 §5 — 진입(BUY) 집행 정책 add-only JSONB 컬럼.
-- EntryPolicy(maxEntrySlippagePct·entryTimeoutSec·maxSpreadPct) 묶음 객체 직렬화 슬롯.
-- null = 현행(전역 default env magicjar.order.entry.* 적용) → 기존 전략 0 변경(하위호환 LOCK · ScaleInPlan scale_in_config 선례 1:1).
ALTER TABLE strategy
    ADD COLUMN entry_policy jsonb;

COMMENT ON COLUMN strategy.entry_policy IS
    'ADR 0114 진입(BUY) 집행 정책 — { maxEntrySlippagePct, entryTimeoutSec, maxSpreadPct }. null=전역 default(MARKET 진입 현행).';
