-- V66: order_request.source — EXTERNAL 값 허용 (CHECK 제약 확장)
--
-- ### 배경 (2026-06-05 자본 path fix — 외부 HTS 주문 흡수 오기록)
-- 사용자가 KIS HTS 앱에서 직접 낸 주문(예: 004090 5주 매수)을 시스템이 체결통보(H0STCNI9) 또는
-- REST 일별체결 backfill로 흡수할 때, OrderRequest stub row의 source가 'AUTO'로 오기록됐다.
-- 외부 주문은 magicJar 자동매매(AUTO)도 사용자 수동 주문 API(MANUAL)도 아니므로 별도 분류가 필요하다.
--
-- ### 변경 (add-only · 값 확장만)
-- V37 본문: CHECK (source IN ('AUTO','MANUAL')) → 'EXTERNAL' INSERT 시 23514 위반.
-- 본 migration은 기존 CHECK 제약을 DROP 후 'EXTERNAL' 포함하여 재생성한다.
-- DEFAULT 'AUTO'·INDEX(idx_order_request_source)·컬럼 타입은 그대로 (V37 유지).
--
-- ### IF EXISTS / 동적 탐색 의무 (§15-1 LOCK)
-- V37 CHECK 제약 이름은 ADD COLUMN ... CHECK 인라인 정의라 PG가 자동 채번
-- (보통 order_request_source_check). 환경별 재실행·기존 DB 상태 차이를 고려해
-- pg_constraint에서 source 컬럼 대상 CHECK 제약을 동적 탐색 후 DROP → 명시 이름으로 재생성.
--
-- ### 자본 안전 / canary 정합
-- EXTERNAL 주문은 ADR 0109 canary 일일 cap(mode=REAL count)에서 제외 대상 (외부 주문은 우리 자동매매 예산이 아님).
-- count 쿼리(countRealOrdersBetween)에 source <> 'EXTERNAL' 조건이 함께 들어간다 (application level).
--
-- ### KIS-first raw 출처 (헌법 §3)
-- 본 migration은 KIS endpoint 신규 호출 0건 — 데이터 분류 값 확장만 (raw 인용 N/A).
--
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0082 + 2026-06-05 외부 주문 흡수 fix)

-- 1. 기존 source CHECK 제약 동적 탐색 후 DROP
DO $$
DECLARE
    cons_name TEXT;
BEGIN
    FOR cons_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'order_request'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%source%IN%'
    LOOP
        EXECUTE format('ALTER TABLE order_request DROP CONSTRAINT %I', cons_name);
    END LOOP;
END
$$;

-- 2. EXTERNAL 포함 CHECK 제약 재생성 (명시 이름 — 후속 migration 추적성)
ALTER TABLE order_request
    ADD CONSTRAINT chk_order_request_source
        CHECK (source IN ('AUTO', 'MANUAL', 'EXTERNAL'));

COMMENT ON COLUMN order_request.source IS
    '2026-06-05 외부 주문 흡수 fix — AUTO(자동매매 signal path) / MANUAL(POST /api/orders/manual) / EXTERNAL(KIS HTS·MTS 외부 직접 주문을 체결통보·REST backfill이 흡수). EXTERNAL은 canary 일일 cap 제외.';
