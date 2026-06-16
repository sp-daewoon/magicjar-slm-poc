package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 상태 전이 (ADR 0068 §14-1 enum LOCK + §5-1 정합 + ADR 0084 P0-2 PENDING_RECONCILE 합류).
 *
 * 전이도:
 *   REQUESTED → ACK → PARTIALLY_FILLED → FILLED
 *   REQUESTED → REJECTED (3중 게이트 / 멱등 / KIS REST 4xx)
 *   REQUESTED → PENDING_RECONCILE (KIS REST 5xx 재시도 후 실패 또는 timeout — ADR 0084 P0-2)
 *   PENDING_RECONCILE → ACK     (KIS 주문조회 결과 ODNO 발견 — 실제 접수됐던 케이스)
 *   PENDING_RECONCILE → REJECTED (KIS 주문조회 결과 ODNO 미발견 — KIS 미접수 확정)
 *   PENDING_RECONCILE → EXPIRED  (15분 timeout 까지도 결정 불가 — 운영자 수동 검증 필요)
 *   ACK       → CANCELLED (사용자 [취소] → KIS order-rvsecncl 성공)
 *   ACK       → EXPIRED   (1차 미도입 — 후속 자동 취소 worker용)
 *
 * ### PENDING_RECONCILE 합류 사유 (ADR 0084 P0-2)
 * 외부 코드 리뷰 결함 #3 — KIS REST timeout 또는 5xx 재시도 후 실패 시 현행 path는 즉시 REJECTED 마킹.
 * 그러나 KIS는 주문을 실제 접수했을 수 있어 — 클라이언트는 ACK 미수신이지만 KIS 서버는 ODNO 발급.
 * 이때 REJECTED 마킹은 자본 audit 불일치 발생 (실제 매수/매도 발생 but DB는 REJECTED).
 * PENDING_RECONCILE 상태로 보류 → reconciliation worker가 inquire-daily-ccld 호출로 실제 상태 검증.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderStatus {
    REQUESTED,
    ACK,
    FILLED,
    PARTIALLY_FILLED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    PENDING_RECONCILE,
}
