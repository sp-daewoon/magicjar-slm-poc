-- 2026-06-04 운영 보정 — backlog §82-1 (KisExecutionBackfillService 재마킹 결함)
--
-- 사유:
--   KIS WS가 매시 정각 재연결될 때마다 onReconnect backfill 이 실행되었고,
--   이미 FILLED 인 주문도 syncOrderStatusAfterBackfill 의 종결 상태 가드 부재로
--   markCompleted(orderId, now, FILLED) 가 재실행되어 order_request.completed_at 이
--   매시간 now 로 덮어써졌다. 그 결과 2026-06-04 FILLED 주문 다수의 completed_at 이
--   마지막 backfill 시각(예: 13:00:11)으로 동일하게 오염되었다.
--
--   실제 체결 시각은 order_execution.executed_at 에 보존되어 있으므로,
--   각 주문의 마지막 체결 시각(max(executed_at)) 으로 completed_at 을 복원한다.
--
-- 안전 조건:
--   - status = 'FILLED' 인 주문만 대상 (종결 권위 시각이 1회만 마킹돼야 하는 상태).
--   - requested_at >= '2026-06-04' 로 본 결함 발생 윈도우로 한정.
--   - completed_at > max(executed_at) 인 경우만 (오염된 row만 — 정상 row 불변).
--
-- 코드 fix: agent/backend-core/fix-backfill-refilled-remark (syncOrderStatusAfterBackfill 종결 상태 no-op 가드).
-- 실행: orchestrator 가 코드 PR 머지 후 운영 DB 에 수동 적용.

UPDATE order_request o
SET completed_at = e.max_executed
FROM (
    SELECT order_id, max(executed_at) AS max_executed
    FROM order_execution
    GROUP BY order_id
) e
WHERE e.order_id = o.id
  AND o.status = 'FILLED'
  AND o.requested_at >= '2026-06-04'
  AND o.completed_at > e.max_executed;
