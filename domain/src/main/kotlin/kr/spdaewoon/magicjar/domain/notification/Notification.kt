package kr.spdaewoon.magicjar.domain.notification

import java.time.Instant
import java.util.UUID

/**
 * 알림 이벤트 도메인 (ADR 0044 + ADR 0070 §2-4).
 *
 * Kafka 수신 이벤트를 알림 발송용 도메인 모델로 표현.
 */
data class NotificationEvent(
    val topic: String,
    val payload: Map<String, Any>,
)

/**
 * 알림 발송 페이로드 — outbox INSERT 시 사용 (ADR 0044 + ADR 0070 §3-3 spec §2-4 LOCK).
 *
 * @param eventId   UUID (dedup 키 — notification:dedup:{eventId} Redis SETNX 24h)
 * @param eventType 알림 이벤트 유형 (7종 LOCK — ADR 0070 §2-4)
 * @param priority  P0/P1/P2 (ADR 0044 §2-2 정합)
 * @param userId    사용자 ID (마스킹은 formatter에서)
 * @param reason    human-readable 사유 (symbol·rule·reason 필드 조합)
 * @param meta      추가 메타 (JSONB payload 저장)
 * @param createdAt 생성 시각
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
data class NotificationPayload(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: NotificationEventType,
    val priority: NotificationPriority,
    val userId: String,
    val reason: String,
    val meta: Map<String, String?> = emptyMap(),
    val createdAt: Instant = Instant.now(),
)

/**
 * 알림 아웃박스 도메인 (ADR 0044 — outbox 패턴 + ADR 0070 §3-3 Flyway V27 정합).
 *
 * PENDING → SENT / FAILED 상태 전이.
 * retry_count ≥ 5 시 FAILED 전환 (5분 retry worker 관리).
 */
data class NotificationOutbox(
    val id: Long? = null,
    val eventId: UUID,
    val channel: NotificationChannel = NotificationChannel.SLACK,
    val priority: NotificationPriority,
    val payloadJson: String,
    val status: NotificationStatus = NotificationStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Instant = Instant.now(),
    val sentAt: Instant? = null,
)

/**
 * 알림 채널 (ADR 0044 — SLACK 단일, FUTURE: EMAIL·SMS·PUSH·DISCORD).
 */
enum class NotificationChannel {
    SLACK,
}

/** 하위 호환성 alias — 기존 코드 참조 회피용 (OutboxStatus → NotificationStatus 전환 완료 후 제거 가능) */
@Deprecated("NotificationStatus로 통합", ReplaceWith("NotificationStatus", "kr.spdaewoon.magicjar.domain.notification.NotificationStatus"))
typealias OutboxStatus = NotificationStatus
