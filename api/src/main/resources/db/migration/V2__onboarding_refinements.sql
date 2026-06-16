-- =============================================================================
-- magicJar V2 — Onboarding refinements (PR-6 T-ONBOARD)
-- ADR 0046 정합 — masked_account GENERATED · 인덱스 · created_at/updated_at
-- =============================================================================

-- 1. user_account.masked_account → GENERATED ALWAYS AS (ADR 0046 §2-1 B)
--    PostgreSQL 16은 기존 컬럼을 GENERATED로 ALTER 불가 → DROP + ADD
--    V1 baseline에서 row 0건이라 데이터 손실 없음
ALTER TABLE user_account DROP COLUMN masked_account;
ALTER TABLE user_account ADD COLUMN masked_account VARCHAR(20) GENERATED ALWAYS AS
    (substring(user_id, 1, 4) || '****') STORED;

-- 2. user_account.last_login_at 인덱스 (ADR 0046 §2-1 B)
CREATE INDEX IF NOT EXISTS idx_user_account_last_login ON user_account (last_login_at);

-- 3. user_kis_credential.created_at·updated_at 추가 (ADR 0046 §2-1 C)
ALTER TABLE user_kis_credential ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE user_kis_credential ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
