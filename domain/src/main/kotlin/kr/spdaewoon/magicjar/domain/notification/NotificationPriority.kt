package kr.spdaewoon.magicjar.domain.notification

/**
 * 알림 우선도 (ADR 0044 §2-2 + ADR 0070 §2-4 P0/P1/P2 정합).
 *
 * - P0: 즉시 단건 전송 (rate limit 없음) — TRADE·RISK_VIOLATION·RECONCILE_DRIFT·KILLSWITCH_ENGAGED
 * - P1: Redis sliding window 누적 후 윈도 단위 1회 — reserved
 * - P2: dev profile skip 가능 — ORDER_CANCELLED·RECONCILE_RUN
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
enum class NotificationPriority {
    P0,
    P1,
    P2,
}
