-- V18: position 테이블 확장 — T-RISK Phase 2 (ADR 0067 §5-4 G-1 + ADR 0042 정합)
-- 기존 ADR 0042 baseline position 테이블에 T-RISK 필요 컬럼 추가
-- 기존 schema: user_id VARCHAR(20)·code·quantity INT·avg_price·updated_at
-- T-RISK 추가: source(MOCK/REAL) · entry_ts · qty(BIGINT alias)
--
-- 2026-05-12 hotfix: V18 신규 CREATE TABLE → ALTER 패턴 정정 (기존 테이블 존재 정합)

-- 1. T-RISK 필수 컬럼 추가 (IF NOT EXISTS — 멱등)
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS source VARCHAR(10) NOT NULL DEFAULT 'MOCK'
        CONSTRAINT chk_position_source CHECK (source IN ('MOCK', 'REAL'));

ALTER TABLE position
    ADD COLUMN IF NOT EXISTS entry_ts TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 2. quantity → qty BIGINT alias 컬럼 추가 (기존 quantity INT 그대로 유지, 코드는 qty 우선 사용)
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS qty BIGINT NOT NULL DEFAULT 0;

-- 3. quantity → qty 동기화 (기존 데이터 백필)
UPDATE position SET qty = quantity WHERE qty = 0 AND quantity > 0;

-- 4. T-RISK 인덱스 보강 (기존 idx_position_user 그대로, 추가 인덱스 0)
COMMENT ON COLUMN position.source     IS 'MOCK·REAL — TradingMode 정합 (T-RISK Phase 2)';
COMMENT ON COLUMN position.entry_ts   IS '최초 진입 ts (T-RISK Phase 2)';
COMMENT ON COLUMN position.qty        IS '보유 수량 (BIGINT — quantity와 동기화, qty 우선)';
