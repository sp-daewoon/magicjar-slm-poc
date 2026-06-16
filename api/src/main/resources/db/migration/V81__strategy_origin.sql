-- V81: strategy — origin 출처 enum (사용자 DSL vs 발굴 페이퍼 승격)
--      (ADR 0122 §2-A R2 영속 · spec 2026-06-10-daytrade-mock-paper-dispatch-spec §3-R1 계약 1)
--
-- ### 배경 (ADR 0122 — 발굴→MOCK 페이퍼 dispatch 경로)
-- 발굴(Discovery) 후보를 사용자 opt-in으로 "모의(페이퍼)로 굴려보기" 승격하면, 그 setup을
-- origin=PAPER_DISCOVERY 전략 인스턴스로 만들어 기존 signal→risk→order 경로(MOCK 강제)에 태운다.
-- 사용자 DSL 전략(USER_DSL)과 페이퍼 승격 전략(PAPER_DISCOVERY)을 데이터 차원에서 식별해야
-- consumer capital 분기(가상 자본 vs 실 KIS 잔고)가 origin으로 판정한다(spec §1-3 격리 LOCK).
--
-- ### 변경 (add-only · nullable 컬럼 1개 + 기존 행 USER_DSL backfill)
--   origin VARCHAR(24) NULL  — USER_DSL / PAPER_DISCOVERY (R1 StrategyOrigin enum 정합)
-- nullable add-only — 기존 row는 USER_DSL backfill (하위호환 zero-regression). default USER_DSL.
--
-- ### 자본 안전 (헌법 §6·§11)
-- 본 migration은 주문 로직 변경 0 — 출처 메타 컬럼 add + backfill만. 자본 흐름·체결·잔고 영향 없음.
-- 페이퍼 주문은 신규 OrderTrigger 발명 없이 STRATEGY_SIGNAL(V80) + mode=MOCK + origin 조합으로 식별(ADR 0122 §2-E).
--
-- ### KIS-first raw 출처 (헌법 §3)
-- KIS endpoint 신규 호출 0건 — 데이터 메타 정비만 (raw 인용 N/A).
--
-- AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1)

-- 1. 컬럼 추가 (add-only · nullable)
ALTER TABLE strategy
    ADD COLUMN IF NOT EXISTS origin VARCHAR(24);

-- 2. CHECK 제약 — 허용 enum 값 (NULL 허용 = legacy 미분류 = USER_DSL 취급)
ALTER TABLE strategy
    DROP CONSTRAINT IF EXISTS chk_strategy_origin;
ALTER TABLE strategy
    ADD CONSTRAINT chk_strategy_origin
        CHECK (origin IS NULL OR origin IN ('USER_DSL', 'PAPER_DISCOVERY'));

-- 3. 기존 행 backfill (전부 USER_DSL — 발굴 페이퍼는 V81 이후에만 생성)
UPDATE strategy
SET origin = 'USER_DSL'
WHERE origin IS NULL;

-- 4. 조회 인덱스 (페이퍼 전략 목록·정합 검증 — add-only)
CREATE INDEX IF NOT EXISTS idx_strategy_origin
    ON strategy (origin);

COMMENT ON COLUMN strategy.origin IS
    'ADR 0122 §2-A — 전략 출처 enum. USER_DSL(사용자 작성 DSL · default) / PAPER_DISCOVERY(발굴 setup 페이퍼 승격). NULL=legacy(USER_DSL 취급). consumer capital 분기 판정 키(가상 자본 vs 실 KIS 잔고 · spec §1-3 격리).';
