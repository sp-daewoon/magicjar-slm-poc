package kr.spdaewoon.magicjar.domain.notification

/**
 * 알림 이벤트 유형 enum (ADR 0044 + ADR 0070 §2-4 LOCK + ADR 0084 P0-4 superset).
 *
 * Kafka 7 listener 매트릭스 정합:
 * - ORDER_FILLED       → order.filled.v1       (P0, TRADE 즉시)
 * - ORDER_REJECTED     → order.rejected.v1     (P0, TRADE 즉시)
 * - ORDER_CANCELLED    → order.cancelled.v1    (P2)
 * - RISK_VIOLATION     → risk.violation.v1     (P0, 반복 보호 차단 일부는 Slack dedupe)
 * - RECONCILE_DRIFT    → reconcile.drift.v1    (P0)
 * - KILLSWITCH_ENGAGED → killswitch.engaged.v1 (P0)
 * - RECONCILE_RUN      → reconcile.run.v1      (P2 — audit only)
 * - DLT_RECEIVED       → order.*.v1.DLT 4건    (P0 — ADR 0084 P0-4)
 * - SYSTEM_EVENT       → system.event.v1       (severity 기반 P0/P2)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
enum class NotificationEventType(
    val defaultPriority: NotificationPriority,
) {
    ORDER_FILLED(NotificationPriority.P0),
    ORDER_REJECTED(NotificationPriority.P0),
    ORDER_CANCELLED(NotificationPriority.P2),
    RISK_VIOLATION(NotificationPriority.P0),
    RECONCILE_DRIFT(NotificationPriority.P0),
    KILLSWITCH_ENGAGED(NotificationPriority.P0),
    RECONCILE_RUN(NotificationPriority.P2),

    /**
     * DLT(Dead Letter Topic) 수신 알림 (ADR 0084 P0-4).
     *
     * Kafka listener 무한 재시도 fix — 1초 간격 3회 재시도 후 DLT 발사.
     * order.*.v1.DLT 4건 (order.intent / order.requested / order.rejected / order.filled) 수신 시
     * OrderDltMonitorListener가 P0 알림 즉시 발사 (자본 path 잠재 risk).
     */
    DLT_RECEIVED(NotificationPriority.P0),

    /**
     * SystemEvent WARN/ERROR/CRITICAL 외부 알림 bridge.
     *
     * 실제 priority는 severity별로 dispatcher가 override:
     * CRITICAL·ERROR → P0 즉시, WARN → P2 묶음/지연, INFO → skip.
     */
    SYSTEM_EVENT(NotificationPriority.P2),
}
