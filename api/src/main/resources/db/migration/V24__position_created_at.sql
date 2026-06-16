-- V24: position 테이블 created_at 컬럼 추가 — JPA 엔티티 정합
-- PositionJpaEntity.createdAt 필드가 매핑하는 컬럼 누락 수정
-- (ADR 0051 JPA validate — schema-validation 오류 수정)

ALTER TABLE position
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

COMMENT ON COLUMN position.created_at IS '최초 포지션 생성 ts (JPA PositionJpaEntity 정합)';
