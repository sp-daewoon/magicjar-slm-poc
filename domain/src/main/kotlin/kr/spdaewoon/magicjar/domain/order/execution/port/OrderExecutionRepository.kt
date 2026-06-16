package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution

/**
 * OrderExecution(분할 체결) 영속 포트 (ADR 0068 §14-1 LOCK · 헌법 §6 abstract만).
 *
 * [sumExecutedQuantityByOrder] 누적 합으로 PARTIALLY_FILLED → FILLED 전이 판정 (§14-6).
 *
 * ### ADR 0084 P0-3 추가 — WS race + 재연결 backfill 멱등성
 * [existsByEventId] · [findByEventId] — WS 체결 통보 + REST inquire-daily-ccld backfill 양 경로 dedup.
 * 동일 체결 row 중복 INSERT 회피 — Flyway V35 event_id UNIQUE constraint 1차 + 본 abstract fun 2차 가드.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §2-8)
 */
interface OrderExecutionRepository {
    fun save(execution: OrderExecution): OrderExecution

    fun findByOrderId(orderId: Long): List<OrderExecution>

    fun sumExecutedQuantityByOrder(orderId: Long): Int

    /**
     * ADR 0084 P0-3 — event_id 기준 멱등 가드.
     * WS 또는 REST backfill INSERT 직전 체크 — true 시 skip.
     */
    fun existsByEventId(eventId: String): Boolean
}
