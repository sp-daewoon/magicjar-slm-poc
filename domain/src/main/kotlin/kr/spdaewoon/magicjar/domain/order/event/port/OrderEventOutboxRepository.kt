package kr.spdaewoon.magicjar.domain.order.event.port

import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxStatus

/**
 * 주문 이벤트 아웃박스 영속 포트 (ADR 0084 P1-1 + Flyway V40 정합).
 *
 * 헌법 §6 (ADR 0051) — interface 내부 abstract fun만 (body 0).
 * helper / branching 은 api 모듈 어댑터 private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
interface OrderEventOutboxRepository {
    /** outbox row 신규 INSERT (PENDING 상태 default) */
    fun save(outbox: OrderEventOutbox): OrderEventOutbox

    /** PENDING 상태 outbox 조회 (worker 용 — published_at IS NULL · 오래된 순) */
    fun findPending(limit: Int = 100): List<OrderEventOutbox>

    /** publish 성공 처리 (status=PUBLISHED, published_at=now()) */
    fun markPublished(id: Long): OrderEventOutbox?

    /** publish 시도 실패 처리 (attempt_count++ · last_error 갱신 · status는 PENDING 유지) */
    fun recordAttemptFailure(
        id: Long,
        error: String,
    ): OrderEventOutbox?

    /** 한계 초과 시 FAILED 전환 (운영 알림 동반) */
    fun markFailed(
        id: Long,
        error: String,
    ): OrderEventOutbox?

    /** 상태별 조회 (운영 디버깅) */
    fun findByStatus(
        status: OrderOutboxStatus,
        limit: Int = 1000,
    ): List<OrderEventOutbox>
}
