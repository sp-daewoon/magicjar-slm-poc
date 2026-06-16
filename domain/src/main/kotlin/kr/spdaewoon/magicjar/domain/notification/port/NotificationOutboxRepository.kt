package kr.spdaewoon.magicjar.domain.notification.port

import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus

/**
 * 알림 아웃박스 영속 포트 (ADR 0044 + ADR 0070 §3-3 Flyway V27 정합).
 *
 * 헌법 §6 (ADR 0051) — abstract fun만 (body 0).
 * 구현은 api 모듈 NotificationOutboxRepositoryAdapter.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2)
 */
interface NotificationOutboxRepository {
    /** PENDING 상태 아웃박스 조회 (retry worker 용) */
    fun findPending(limit: Int = 100): List<NotificationOutbox>

    /** 상태별 조회 */
    fun findByStatus(status: NotificationStatus): List<NotificationOutbox>

    /** 아웃박스 저장 */
    fun save(outbox: NotificationOutbox): NotificationOutbox

    /** 발송 성공 처리 (status=SENT, sent_at=now()) */
    fun markSent(id: Long): NotificationOutbox?

    /** 발송 실패 처리 (status=FAILED, last_error 업데이트, retry_count 증가) */
    fun markFailed(
        id: Long,
        error: String,
    ): NotificationOutbox?

    /** 재시도 카운트 증가 (retry 후 여전히 PENDING 유지) */
    fun incrementRetryCount(id: Long): NotificationOutbox?
}
