package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest

/**
 * 주문 STOMP 알림 포트 (ADR 0068 §14-1 LOCK · 의제 G-1).
 *
 * /topic/order.user.{userId} 4 event fanout (§15-4 backend-core 어댑터):
 *   notifyRequested  — OrderRouterKafkaListener step 6
 *   notifyFilled     — KisExecutionWsListener CNTG_YN="2" (partial 또는 full)
 *   notifyRejected   — OrderRouterKafkaListener 3·4·7 step REJECTED 분기
 *   notifyCancelled  — KisExecutionWsListener ACPT_YN="3"
 *
 * Phase 1 consumer는 4 Kafka producer publish (`order.requested.v1`·`order.filled.v1`·
 * `order.rejected.v1`·`order.cancelled.v1`). Phase 2 backend-core가 listen → STOMP fanout.
 * 본 port는 Phase 1 직접 호출 0 (Kafka 경유). 도메인 시그니처 LOCK 유지용.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderNotifier {
    fun notifyRequested(request: OrderRequest)

    fun notifyFilled(
        request: OrderRequest,
        execution: OrderExecution,
        partial: Boolean,
    )

    fun notifyRejected(
        request: OrderRequest,
        reason: String,
    )

    fun notifyCancelled(request: OrderRequest)
}
