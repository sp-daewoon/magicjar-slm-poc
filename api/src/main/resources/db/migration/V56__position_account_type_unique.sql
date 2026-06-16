-- V56: position REAL/MOCK 분리 보장
--
-- 기존 uq_position_user_code(user_id, code)는 같은 사용자가 같은 종목을 REAL/MOCK 양쪽에
-- 보유할 때 한쪽 row가 다른 쪽을 덮어쓰게 만든다. source(MOCK/REAL)를 계좌 유형으로 승격해
-- 포지션을 계좌별로 독립 보존한다.

ALTER TABLE position
    DROP CONSTRAINT IF EXISTS uq_position_user_code;

CREATE UNIQUE INDEX IF NOT EXISTS uq_position_user_code_source
    ON position (user_id, code, source);

CREATE INDEX IF NOT EXISTS idx_position_user_source_qty
    ON position (user_id, source, quantity);

COMMENT ON INDEX uq_position_user_code_source IS
    'REAL/MOCK 계좌별 포지션 독립 보존 — 같은 user/code라도 source가 다르면 별도 row';
