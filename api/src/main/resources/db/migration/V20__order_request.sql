-- V20: order_request 테이블 신규 — T-O Phase 2 (ADR 0068 §6-1 + §15-1 LOCK)
--
-- IF NOT EXISTS 의무: V18 ADR 0042 충돌 패턴 재발 회피 (§15-1 LOCK)
-- signal_id FK ON DELETE RESTRICT: signal 삭제 시 order 보존 (감사 정합)
-- idempotency_key UQ: H-1 Redis SETNX + DB UQ 이중 자본 보호 LOCK
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)

CREATE TABLE IF NOT EXISTS order_request (
    id                BIGSERIAL PRIMARY KEY,
    signal_id         BIGINT NOT NULL REFERENCES signal(id) ON DELETE RESTRICT,
    user_id           VARCHAR(40) NOT NULL,
    code              VARCHAR(8) NOT NULL,
    side              VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    qty               NUMERIC(15,4) NOT NULL CHECK (qty > 0),
    price_type        VARCHAR(10) NOT NULL CHECK (price_type IN ('MARKET','LIMIT')),
    limit_price       NUMERIC(19,4),
    status            VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','ACK','FILLED','PARTIALLY_FILLED','REJECTED','CANCELLED','EXPIRED')),
    kis_order_no      VARCHAR(20),
    kis_fwdg_orgno    VARCHAR(20),
    mode              VARCHAR(4) NOT NULL CHECK (mode IN ('MOCK','REAL')),
    idempotency_key   VARCHAR(64) NOT NULL,
    reject_reason     VARCHAR(255),
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at   TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uq_order_signal_user UNIQUE (signal_id, user_id),
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_order_user_ts ON order_request (user_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_kis_no ON order_request (kis_order_no);
CREATE INDEX IF NOT EXISTS idx_order_status_open ON order_request (user_id, status)
    WHERE status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED');

COMMENT ON TABLE order_request IS 'T-O 주문 의도 (ADR 0068 §6-1). idempotency_key UQ + signal_id ON DELETE RESTRICT 자본 보호.';
COMMENT ON COLUMN order_request.mode IS 'MOCK·REAL — 3중 게이트 결과 LOCK (ADR 0068 §14-3)';
COMMENT ON COLUMN order_request.idempotency_key IS 'Redis SETNX 60s + DB UQ 이중 (ADR 0068 H-1 의제 — 자본 중복 주문 방지)';
