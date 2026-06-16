-- V45: order_request.source legacy manual backfill
--
-- 2026-05-20 초반 수동 주문 일부가 source 컬럼 도입 전후 전환기 때문에
-- idempotency_key='manual:%' / signal_id=0 sentinel 인데 source='AUTO'로 남았다.
-- 화면 출처 필터와 감사 표기가 실제 주문 진입점과 일치하도록 MANUAL로 보정한다.

UPDATE order_request
SET source = 'MANUAL'
WHERE source = 'AUTO'
  AND (
    idempotency_key LIKE 'manual:%'
    OR (signal_id = 0 AND strategy_id = 0)
  );
