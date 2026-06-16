package kr.spdaewoon.magicjar.domain.order.port

import kr.spdaewoon.magicjar.domain.common.DomainResult
import kr.spdaewoon.magicjar.domain.order.Order
import kr.spdaewoon.magicjar.domain.order.OrderIntent

/**
 * 주문 전송 포트 (ADR 0011).
 * 구현은 consumer 모듈 order 어댑터에서 (KIS REST API 호출).
 */
interface OrderSender {
    fun send(intent: OrderIntent): DomainResult<Order>
}
