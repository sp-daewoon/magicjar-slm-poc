-- V36: order_request.status — PENDING_RECONCILE 추가 (ADR 0084 §2-2 P0-2 — 외부 코드 리뷰 결함 #3 fix)
--
-- 배경:
--   외부 코드 리뷰 13 코멘트 중 #3 — KIS REST 5xx 재시도 후 실패 또는 timeout 시 현행 path는 즉시 REJECTED 마킹.
--   그러나 KIS는 주문을 실제 접수했을 수 있어 — 클라이언트 ACK 미수신 ≠ KIS 미접수.
--   PENDING_RECONCILE 상태로 보류 → OrderReconciliationWorker cron 1분 → KIS inquire-daily-ccld로 실제 상태 검증.
--
-- 변경:
--   1. order_request.status CHECK 제약 갱신 — 'PENDING_RECONCILE' 허용
--   2. idx_order_request_pending_reconcile partial index — worker lookup 효율화
--
-- 영향 ADR: 0084 (Proposed) · 0068 (T-O §14-2 step 7·8 superset) · 0070 (RECON cron 매트릭스 §2-9 superset)
-- raw 인용: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_ccld/inquire_daily_ccld.py
-- AI 호출 0 — T-O 트랙 정책 (ADR 0068 §8)

-- 기존 CHECK 제약은 V20에서 inline anonymous로 생성됨 (`status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED' CHECK (...)`).
-- PostgreSQL은 inline CHECK 제약에 자동으로 `<table>_<column>_check` 형태 이름을 부여 — `order_request_status_check`.
-- DO 블록으로 모든 inline CHECK를 안전하게 검사·제거 후 명시적 이름으로 재생성한다.

DO $$
DECLARE
    cons_name TEXT;
BEGIN
    FOR cons_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'order_request'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE order_request DROP CONSTRAINT %I', cons_name);
    END LOOP;
END
$$;

ALTER TABLE order_request
    ADD CONSTRAINT chk_order_status CHECK (
        status IN (
            'REQUESTED',
            'ACK',
            'FILLED',
            'PARTIALLY_FILLED',
            'REJECTED',
            'CANCELLED',
            'EXPIRED',
            'PENDING_RECONCILE'
        )
    );

-- partial index — OrderReconciliationWorker.findByStatusAndRequestedAtBefore 1분 cron lookup 효율화.
-- PENDING_RECONCILE 상태는 평상시 0건 — partial index로 storage cost 최소화.
CREATE INDEX IF NOT EXISTS idx_order_request_pending_reconcile
    ON order_request (requested_at)
    WHERE status = 'PENDING_RECONCILE';

COMMENT ON CONSTRAINT chk_order_status ON order_request IS
    'ADR 0084 P0-2 — PENDING_RECONCILE 추가 (KIS REST 5xx/timeout 후 reconciliation worker 검증 대기 상태).';
COMMENT ON INDEX idx_order_request_pending_reconcile IS
    'ADR 0084 P0-2 — OrderReconciliationWorker cron 1분 lookup 효율화 (partial index, PENDING_RECONCILE 상태만 인덱싱).';
