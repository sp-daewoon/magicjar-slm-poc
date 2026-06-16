package kr.spdaewoon.magicjar.domain.journal

/**
 * 매매일지 이벤트 타입 enum.
 *
 * 신규 매매일지는 ORDER_FILLED만 저장/노출한다.
 * 나머지 값은 과거 row와 DB 호환을 위해 유지한다.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
enum class JournalEventType {
    /** order.filled.v1 → 체결 이벤트 */
    ORDER_FILLED,

    /** order.rejected.v1 → 주문 거부 이벤트 */
    ORDER_REJECTED,

    /** order.cancelled.v1 → 주문 취소 이벤트 */
    ORDER_CANCELLED,

    /** signal.generated.v1 → 시그널 발생 이벤트 */
    SIGNAL_GENERATED,

    /** risk.violation.v1 → 리스크 위반 이벤트 */
    RISK_VIOLATION,
}
