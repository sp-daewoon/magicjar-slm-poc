package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 감사 이벤트 유형 (ADR 0068 §14-1 enum LOCK + ADR 0084 P0-2 reconciliation 4건 합류).
 *
 * §14-2 OrderRouterKafkaListener 12 step + §14-4 risk 매트릭스 + §14-6 흐름 분기 LOCK 정합.
 *
 * ### ADR 0084 P0-2 신규 audit (외부 코드 리뷰 결함 #3 — reconciliation worker)
 * - KIS_RECONCILE_PENDING: 5xx 재시도 후 실패 → PENDING_RECONCILE 상태 마킹
 * - KIS_RECONCILE_RESOLVED: reconciliation worker가 KIS 주문조회로 ACK 또는 REJECTED 확정
 * - KIS_RECONCILE_TIMEOUT: 15분 timeout 까지도 결정 불가 → EXPIRED 마킹
 * - KIS_TIMEOUT: REST timeout (socket timeout) 발생 — submitWithRetry 분기점
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderAuditEventType {
    REAL_GATE_DENIED,
    IDEMPOTENCY_SKIPPED,
    KILLSWITCH_DENIED,
    KIS_ACK,
    KIS_REJECT,
    KIS_EXCEPTION,
    KIS_INVALID_RESPONSE,
    FILL_RECEIVED,
    PARTIAL_FILL,
    FULL_FILL,
    KIS_CANCEL_ACK,
    KIS_CANCEL_REJECT,
    EXECUTION_TIMEOUT,
    EXECUTION_OVERFLOW,
    REDIS_DOWN_FALLBACK,
    INVALID_ORDER_INTENT,
    KIS_EXECUTION_SYNC_STALE,
    KIS_TIMEOUT,
    KIS_RECONCILE_PENDING,
    KIS_RECONCILE_RESOLVED,
    KIS_RECONCILE_TIMEOUT,
    ORDER_DB_SYNC_FAILED,
}
