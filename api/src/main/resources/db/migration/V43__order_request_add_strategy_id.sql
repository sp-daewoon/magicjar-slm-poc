-- V43: order_request.strategy_id 보존 — order event strategy attribution 회복.
--
-- 기존 OrderRequest 도메인은 strategyId를 갖고 있었지만 order_request 테이블에는 컬럼이 없어
-- api/consumer adapter가 조회 시 strategyId=0L로 되돌렸다. 자동매매 order.filled/rejected/cancelled
-- event의 전략 귀속이 유실되므로 add-only 컬럼으로 보강한다.

ALTER TABLE order_request
    ADD COLUMN IF NOT EXISTS strategy_id BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_order_request_strategy_ts
    ON order_request (strategy_id, requested_at DESC);

COMMENT ON COLUMN order_request.strategy_id IS
    '자동매매 전략 ID. MANUAL 주문은 0 sentinel. V43 add-only attribution fix.';
