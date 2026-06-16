package kr.spdaewoon.magicjar.domain.order.execution

import java.time.Instant

/**
 * 주문 감사 이벤트 시계열 도메인 (ADR 0068 §14-1 LOCK · §6-3 order_audit_event 테이블 정합).
 *
 * §14-2 OrderRouterKafkaListener 매 step + §14-6 KisExecutionWsListener 매 분기에서 publish.
 * payload는 JSON 직렬화된 payload (KIS raw 응답·예외 트레이스·denyReason 등).
 *
 * 필드 LOCK (§14-1):
 *   id           — bigserial PK
 *   orderId      — order_request.id FK (idempotency 중복 skip 시 null 허용)
 *   userId       — credential identity
 *   eventType    — [OrderAuditEventType]
 *   payloadJson  — JSON 직렬화 페이로드 (raw KIS / 예외 / denyReason)
 *   createdAt    — INSERT 시각
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
data class OrderAuditEvent(
    val id: Long? = null,
    val orderId: Long?,
    val userId: String,
    val eventType: OrderAuditEventType,
    val payloadJson: String,
    val createdAt: Instant,
)
