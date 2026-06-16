-- V38: order_request — manual 주문 sentinel 허용 FK/UQ relax (ADR 0082 T-MAN B-2-extended LOCK)
--
-- ### 배경 (ADR 0082 §3-5 옵션-Y)
-- T-MAN manual 주문은 signal·strategy 부재 → signal_id=0L sentinel INSERT 의무.
-- V20 본문: `signal_id BIGINT NOT NULL REFERENCES signal(id) ON DELETE RESTRICT` → FK 위반 발생.
-- V20 본문: `CONSTRAINT uq_order_signal_user UNIQUE (signal_id, user_id)` → 동일 사용자의 manual 주문 2건이
--          모두 signal_id=0이면 UQ 위반.
--
-- ### 변경 (옵션-Y micro 분리 — V37 source 컬럼과 의미 분리)
-- 1. signal_id FK constraint DROP (PG 자동 채번 이름 → DO 블록으로 동적 탐색)
-- 2. uq_order_signal_user UNIQUE constraint DROP — manual 주문 다건 허용
--    (idempotency_key UQ는 V20에 별도 — 자본 중복 보호는 idempotency_key + Redis SETNX 권위)
--
-- ### audit 정합
-- - source 컬럼 (V37) 단일 권위 = AUTO vs MANUAL 분기 마커
-- - signal_id=0 sentinel + source='MANUAL' 이중 검증 (consumer step 5 audit 정합 path)
-- - audit_event payloadJson "source" 마커 = DB 컬럼 + JSON 이중
--
-- ### IF EXISTS 의무 (§15-1 LOCK)
-- PG 인스턴스 재실행·기존 DB 상태 따라 constraint 존재 여부 다양 → IF EXISTS / DO 블록 graceful.
--
-- ### 자본 안전 baseline
-- - idempotency_key UQ (V20)는 그대로 유지 → Redis SETNX 60s + DB UQ 이중 자본 중복 보호 LOCK
-- - source='MANUAL' UI 3중 게이트 layer + 60s cooldown + KillSwitch 4 layer 안전망 보상 (ADR 0082 §4-3 risk 매트릭스)
--
-- ### KIS-first raw 출처 (헌법 §3)
-- 본 migration은 schema relax만 — KIS endpoint 호출 0건 (raw 인용 N/A).
--
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)

-- 1. signal_id FK constraint 탐색 후 DROP
DO $$
DECLARE
    cons_name TEXT;
BEGIN
    FOR cons_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'order_request'::regclass
          AND contype = 'f'
          AND pg_get_constraintdef(oid) ILIKE '%signal_id%REFERENCES signal%'
    LOOP
        EXECUTE format('ALTER TABLE order_request DROP CONSTRAINT %I', cons_name);
    END LOOP;
END
$$;

-- 2. uq_order_signal_user UNIQUE constraint DROP (manual sentinel 2건 이상 허용)
ALTER TABLE order_request DROP CONSTRAINT IF EXISTS uq_order_signal_user;

COMMENT ON TABLE order_request IS
    'ADR 0082 T-MAN B-2-extended — signal_id FK + uq_order_signal_user relax (manual sentinel 0L 허용). 자본 중복 보호는 idempotency_key UQ + Redis SETNX 권위.';
