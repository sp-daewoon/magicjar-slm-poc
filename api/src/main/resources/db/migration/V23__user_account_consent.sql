-- V23: user_account.real_trading_consent_at 컬럼 추가 — T-O Phase 2 (ADR 0068 §6-4 + §15-1 LOCK)
--
-- ADD COLUMN IF NOT EXISTS 의무: 기존 테이블 확장 멱등 (§15-1 LOCK)
-- NULL = 미동의 → REAL 주문 BLOCKED (3중 게이트 조건 3 — ADR 0068 §14-3)
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)

ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS real_trading_consent_at TIMESTAMPTZ;

COMMENT ON COLUMN user_account.real_trading_consent_at IS
    'T-O 실계좌 주문 동의 토글 — NULL이면 모의만 가능 (3중 게이트 3번째 조건, ADR 0068 §14)';
