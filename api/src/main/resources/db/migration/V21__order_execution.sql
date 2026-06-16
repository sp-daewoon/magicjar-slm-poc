-- V21: order_execution 테이블 신규 — T-O Phase 2 (ADR 0068 §6-2 + §15-1 LOCK)
--
-- IF NOT EXISTS 의무: V18 ADR 0042 충돌 패턴 재발 회피 (§15-1 LOCK)
-- order_id FK ON DELETE CASCADE: order 삭제 시 execution 정리
-- kis_execution_no UQ 미사용: KIS 일부 환경 미발급 (§15-1 risk 매트릭스 정합)
-- AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)

CREATE TABLE IF NOT EXISTS order_execution (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES order_request(id) ON DELETE CASCADE,
    executed_qty     NUMERIC(15,4) NOT NULL CHECK (executed_qty > 0),
    executed_price   NUMERIC(19,4) NOT NULL,
    executed_at      TIMESTAMPTZ NOT NULL,
    fee              NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax              NUMERIC(19,4) NOT NULL DEFAULT 0,
    kis_execution_no VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_execution_order ON order_execution (order_id);

COMMENT ON TABLE order_execution IS 'T-O 분할 체결 시계열 (ADR 0068 §6-2). order_request 1:N — H0STCNI0 CNTG_YN=2 per 체결.';
COMMENT ON COLUMN order_execution.kis_execution_no IS 'KIS 체결번호 — 일부 환경 미발급으로 UQ 미사용 (§15-1 risk 매트릭스 정합)';
