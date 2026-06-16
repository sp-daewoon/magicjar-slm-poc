package kr.spdaewoon.magicjar.api.controller.order.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 수동 주문 응답 DTO (ADR 0082 T-MAN B-2-extended LOCK · spec §3-1 본문).
 *
 * 202 Accepted 시점 = OrderIntent Kafka publish 완료 + consumer OrderRouter 진입 전.
 * orderId는 null — consumer step 5 INSERT 시점에 채번되므로 endpoint 응답 시점엔 아직 부재.
 * status="ACCEPTED" = endpoint 응답 한정 마커 (OrderRequest.status 매트릭스와 별개 — 혼선 회피).
 *
 * STOMP `/topic/order.user.{userId}` 4 event(REQUESTED·ACK·FILLED·REJECTED) 자동 흡수
 * (frontend useStompOrder hook).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
@Schema(description = "사용자 수동 주문 응답 DTO (POST /api/orders/manual 202 Accepted)")
data class ManualOrderResponseDto(
    @field:Schema(
        description = "주문 ID (Kafka publish 시점이라 아직 채번 0 — consumer step 5 INSERT 시점에 채번)",
        example = "null",
        nullable = true,
    )
    val orderId: Long? = null,
    @field:Schema(
        description = "endpoint 응답 한정 status 마커 (OrderRequest.status 매트릭스와 별개)",
        example = "ACCEPTED",
    )
    val status: String,
    @field:Schema(
        description = "멱등성 키 — \"manual:{userId}:{epochSeconds}\" 형식 (Redis SETNX 60s + DB UQ 이중)",
        example = "manual:74683223:1716073800",
    )
    val idempotencyKey: String,
    @field:Schema(
        description = "사용자 안내 메시지 — UI 토스트용",
        example = "수동 주문이 접수되었습니다. /orders 페이지에서 상태를 확인하세요.",
    )
    val message: String,
)
