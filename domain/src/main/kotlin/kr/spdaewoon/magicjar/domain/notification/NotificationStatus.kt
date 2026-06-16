package kr.spdaewoon.magicjar.domain.notification

/**
 * 알림 아웃박스 상태 enum (ADR 0044 + ADR 0070 §3-3 Flyway V27 정합).
 *
 * - PENDING: 발송 대기 (초기값 or 재시도 대기)
 * - SENT:    발송 성공 (Slack 2xx 응답)
 * - FAILED:  재시도 소진 (retry_count ≥ 5)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
enum class NotificationStatus {
    PENDING,
    SENT,
    FAILED,
}
