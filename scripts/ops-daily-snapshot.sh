#!/usr/bin/env bash
# ops-daily-snapshot.sh — 일일 운영 보고용 데이터 전수 수집 (read-only)
# 사용: scripts/ops-daily-snapshot.sh [YYYY-MM-DD]  (생략 시 오늘 KST)
# 출처: REAL canary 1~2일차 (2026-06-11/12) orchestrator 모니터링 세션에서 정형화.
set -euo pipefail
D="${1:-$(date '+%Y-%m-%d')}"
PSQL="docker exec magicjar-postgres psql -U magicjar -d magicjar"

echo "################ magicJar 일일 운영 스냅샷 — ${D} ################"

echo ""
echo "=== [1] 게이트·모드 상태 (실측) ==="
docker exec magicjar-consumer printenv MAGICJAR_MODE MAGICJAR_ALLOW_REAL MAGICJAR_CANARY_MAX_ORDER_QTY MAGICJAR_CANARY_MAX_ORDER_NOTIONAL_KRW MAGICJAR_CANARY_MAX_ORDERS_PER_DAY MAGICJAR_ORDER_ENTRY_LIMIT_ENABLED 2>/dev/null | paste -sd' ' - || echo "consumer 미기동"
$PSQL -t -A -c "SELECT 'KillSwitch=' || enabled FROM kill_switch_state;" 2>/dev/null
$PSQL -c "SELECT id, name, origin, status FROM strategy WHERE status IN ('ACTIVE','BUY_BLOCKED');" 2>/dev/null | head -8

echo ""
echo "=== [2] 당일 주문 전수 (REAL) ==="
$PSQL -c "SELECT o.id, o.code, o.side, o.qty, o.price_type, o.status, o.reject_reason, o.order_trigger, e.executed_price, o.requested_at::time AS t FROM order_request o LEFT JOIN order_execution e ON e.order_id=o.id WHERE o.requested_at::date='${D}' AND o.mode='REAL' ORDER BY o.id;" 2>/dev/null

echo ""
echo "=== [3] 실현손익·포지션 ==="
$PSQL -c "SELECT strategy_id, quantity, avg_price, realized_pnl FROM strategy_position WHERE realized_pnl <> 0 OR quantity > 0;" 2>/dev/null
$PSQL -c "SELECT code, quantity, source, updated_at::time FROM position WHERE quantity > 0;" 2>/dev/null

echo ""
echo "=== [4] 당일 시그널 통계 (전략·side·판정) ==="
$PSQL -c "SELECT strategy_id, side, risk_status, count(*), min(created_at::time) AS 첫건, max(created_at::time) AS 마지막 FROM signal WHERE created_at::date='${D}' GROUP BY 1,2,3 ORDER BY 1,2,3;" 2>/dev/null

echo ""
echo "=== [5] 당일 알람(outbox) 전수 분류 ==="
$PSQL -c "SELECT payload->>'eventType' AS 이벤트, coalesce(payload->'meta'->>'rule', payload->'meta'->>'action') AS 세부, status, count(*), max(created_at::time) AS 마지막 FROM notification_outbox WHERE created_at::date='${D}' GROUP BY 1,2,3 ORDER BY 4 DESC;" 2>/dev/null

echo ""
echo "=== [6] ERROR 분류 (consumer 24h — 기지 계열 분리) ==="
KNOWN='EGW00201|EGW00215|OPSP8997'
TOTAL=$(docker logs --since 24h magicjar-consumer 2>&1 | grep -c " ERROR " || true)
KNOWN_CNT=$(docker logs --since 24h magicjar-consumer 2>&1 | grep " ERROR " | grep -cE "$KNOWN" || true)
echo "총 ERROR ${TOTAL}건 / 기지 계열(rate limit 등) ${KNOWN_CNT}건 / 신규 의심 $((TOTAL-KNOWN_CNT))건"
echo "--- 신규 의심 상위 5종 ---"
docker logs --since 24h magicjar-consumer 2>&1 | grep " ERROR " | grep -vE "$KNOWN" | sed 's/^[0-9:.]*//; s/code=[0-9]*/code=X/g' | sort | uniq -c | sort -rn | head -5

echo ""
echo "=== [7] 미종결 주문 (stuck 의심 — 휴면 LIMIT 익절은 정상) ==="
$PSQL -c "SELECT id, code, side, price_type, status, requested_at FROM order_request WHERE status IN ('REQUESTED','ACK','PARTIALLY_FILLED','PENDING_RECONCILE') ORDER BY requested_at;" 2>/dev/null

echo ""
echo "=== [8] 원장 정합 (드리프트 24h) ==="
docker logs --since 24h magicjar-consumer 2>&1 | grep -E "STRATEGY_POSITION_DRIFT|REBUILD" | tail -5 || echo "드리프트 로그 0건"

echo ""
echo "=== [9] WS·시세 생존 ==="
for code in $($PSQL -t -A -c "SELECT DISTINCT jsonb_array_elements_text(codes::jsonb) FROM strategy WHERE status='ACTIVE' AND origin='USER_DSL';" 2>/dev/null); do
  AGE=$($PSQL -t -A -c "SELECT coalesce(extract(epoch from (now() - max(ts)))::int, -1) FROM candle WHERE code='${code}' AND \"interval\"='1m';" 2>/dev/null)
  echo "code=${code} 최신 1m 캔들 경과: ${AGE}초"
done
docker logs --since 24h magicjar-consumer 2>&1 | grep -cE "재연결 스케줄|reconnect" | xargs echo "WS 재연결 시도(24h):"

echo ""
echo "################ 스냅샷 끝 ################"
