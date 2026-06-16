package kr.spdaewoon.magicjar.api.service.order

import kr.spdaewoon.magicjar.api.persistence.order.OrderAuditEventRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestRepositoryAdapter
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 주문 조회 서비스 (ADR 0068 §15-2 + §15-3 REST GET 2 endpoint).
 *
 * - 목록 페이징: userId 격리 + status/date range 필터 (ADR 0010 multi-tenant)
 * - 단건: request + executions + audit events 집합
 *
 * 헌법 §6 (ADR 0051): Repository interface body 0건 — Adapter private fun 위임.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Service
class OrderQueryService(
    private val orderRequestAdapter: OrderRequestRepositoryAdapter,
    private val orderExecutionAdapter: OrderExecutionRepositoryAdapter,
    private val orderAuditAdapter: OrderAuditEventRepositoryAdapter,
) {
    private val log = LoggerFactory.getLogger(OrderQueryService::class.java)

    /**
     * 사용자 주문 목록 페이징.
     * userId 격리 — 다른 사용자 row 절대 반환 안 함 (ADR 0010).
     */
    @Transactional(readOnly = true)
    fun listByUser(
        userId: String,
        from: Instant?,
        to: Instant?,
        statuses: List<OrderStatus>?,
        source: OrderSource?,
        side: OrderSide?,
        code: String?,
        page: Int,
        size: Int,
    ): Page<OrderRequest> {
        log.debug(
            "주문 목록 조회: userId={} from={} to={} statuses={} source={} side={} code={} page={} size={}",
            userId,
            from,
            to,
            statuses,
            source,
            side,
            code,
            page,
            size,
        )
        return orderRequestAdapter.findByUserAndRange(userId, from, to, statuses, source, side, code, page, size)
    }

    /**
     * 주문 단건 조회 (request + executions + audit events).
     * userId 격리 — 다른 사용자 orderId → null (404 처리 Controller 위임).
     */
    @Transactional(readOnly = true)
    fun findDetail(
        orderId: Long,
        userId: String,
    ): OrderDetail? {
        val request = orderRequestAdapter.findById(orderId) ?: return null
        if (request.userId != userId) return null
        val executions = orderExecutionAdapter.findByOrderId(orderId)
        val auditEvents = orderAuditAdapter.findByOrderId(orderId)
        return OrderDetail(request, executions, auditEvents)
    }
}

/**
 * 주문 단건 상세 집합 (request + executions + audit events).
 */
data class OrderDetail(
    val request: kr.spdaewoon.magicjar.domain.order.execution.OrderRequest,
    val executions: List<kr.spdaewoon.magicjar.domain.order.execution.OrderExecution>,
    val auditEvents: List<kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent>,
)
