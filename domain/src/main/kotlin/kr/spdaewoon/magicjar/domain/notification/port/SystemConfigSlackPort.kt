package kr.spdaewoon.magicjar.domain.notification.port

/**
 * system_config Slack webhook 조회 포트 (ADR 0046 AES-256-GCM 복호화 정합).
 *
 * 구현: persistence-shared 모듈 SystemConfigSlackAdapter.
 * system_config.notification_slack_webhook BYTEA → 복호화 → String URL.
 * NULL 또는 복호화 실패 시 null 반환 → sender graceful skip.
 *
 * 헌법 §6 (ADR 0051) — abstract fun만.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
interface SystemConfigSlackPort {
    /**
     * Slack webhook URL 조회 (복호화) — 기본 채널.
     * @return URL String or null (미설정 또는 복호화 실패 시)
     */
    fun fetchSlackWebhook(): String?

    /**
     * 주문 체결 알림 전용 추가 Slack webhook URL 조회 (복호화) — system_config.notification_slack_webhook_order (V79).
     *
     * 2026-06-10 최고 관리자 지시 — 주문 체결(ORDER_FILLED)은 기존 채널 유지 + 별도 채널 이중 송출.
     * 미설정(NULL) 또는 복호화 실패 시 null → 추가 송출 silent skip (기존 흐름 영향 0).
     *
     * 헌법 §6 (ADR 0051) — abstract fun만.
     */
    fun fetchOrderSlackWebhook(): String?
}
