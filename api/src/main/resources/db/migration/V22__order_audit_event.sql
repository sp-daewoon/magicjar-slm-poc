-- V22: order_audit_event 테이블 신규 — T-O Phase 2 (ADR 0068 §6-3 + §15-1 LOCK)
--
-- IF NOT EXISTS 의무: V18 ADR 0042 충돌 패턴 재발 회피 (§15-1 LOCK)
-- order_id FK ON DELETE CASCADE: order 삭제 시 audit 정리
-- order_id NULL 허용: IDEMPOTENCY_SKIPPED 시 orderId 없이 audit INSERT (§14-2 step 2 정합)
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)

CREATE TABLE IF NOT EXISTS order_audit_event (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT REFERENCES order_request(id) ON DELETE CASCADE,
    user_id     VARCHAR(40) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    detail_json JSONB,
    ts          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_audit_user_ts ON order_audit_event (user_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_order_audit_action ON order_audit_event (action);

COMMENT ON TABLE order_audit_event IS 'T-O 주문 감사 시계열 (ADR 0068 §6-3). order_id NULL 허용(IDEMPOTENCY_SKIPPED). event_type 매트릭스 §14-1 LOCK.';
COMMENT ON COLUMN order_audit_event.order_id IS 'order_request.id FK — IDEMPOTENCY_SKIPPED 시 NULL (orderId 미생성 상태 감사 의무)';
COMMENT ON COLUMN order_audit_event.action IS 'OrderAuditEventType 값 (§14-1 enum LOCK)';
