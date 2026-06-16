-- V31__order_execution_realized_pnl.sql
-- Hotfix 2026-05-14: T-PERF Phase 2 (ADR 0072) 가 batch/.../BatchOrderExecutionJpaEntity 에
-- `realized_pnl` 컬럼 매핑 추가했으나 Flyway migration 누락 — magicjar-batch 부팅 시
-- Schema-validation 결함:
--   missing column [realized_pnl] in table [order_execution]
--
-- 정정: order_execution 테이블에 realized_pnl 컬럼 ADD COLUMN IF NOT EXISTS.
-- 매수→매도 매칭 후 계산되는 값. 현재 시점 NULL 허용 (계산 로직은 별도 backlog 의제).

ALTER TABLE order_execution
    ADD COLUMN IF NOT EXISTS realized_pnl NUMERIC(18, 2) NULL;
