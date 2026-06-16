package kr.spdaewoon.magicjar.api.service.signal

import kr.spdaewoon.magicjar.api.persistence.order.OrderExecutionRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestRepositoryAdapter
import kr.spdaewoon.magicjar.api.persistence.position.PositionJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 시그널 키 사슬 추적 조회 서비스 — ADR 0115 §2-B 불변식 #4 (화면 추적성).
 *
 * 최고 관리자 직접 요구(2026-06-10): "전략이 사인 내면 몇 주 주문 내고, 몇 주가 그 전략으로
 * 사지고 팔아졌는지를 키로 추적." 1 사인 → 파생 주문 N건 → 체결 타임라인 → 현재 전략 보유 + 정합을
 * 끊김 없이 join한다.
 *
 * 사슬 hop:
 *   signal(id) ──triggerRefId──▶ order_request(STRATEGY_SIGNAL) ──orderId──▶ order_execution(부분체결 N)
 *              └─ strategy_position(user+strategy+code) ←대조→ position(user+code, KIS 권위)
 *
 * **read-only** — 자본 write 0. 모든 데이터는 실제 DB join (mock 합성 0건 — feedback_no_mock_data).
 * 헌법 §6 (ADR 0051): Repository interface body 0건 — JPA query inference / @Query 만 사용.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Service
class SignalTraceService(
    private val signalQueryService: SignalQueryService,
    private val orderRequestAdapter: OrderRequestRepositoryAdapter,
    private val orderExecutionAdapter: OrderExecutionRepositoryAdapter,
    private val strategyPositionReadJpaRepository: StrategyPositionReadJpaRepository,
    private val positionJpaRepository: PositionJpaRepository,
) {
    private val log = LoggerFactory.getLogger(SignalTraceService::class.java)

    /**
     * 시그널 키 사슬 추적 조회. userId 불일치 시 404 (SignalQueryService.get 위임).
     *
     * @param userId 소유자 (격리 — ADR 0010)
     * @param signalId 시그널 ID
     * @return 사인 → 주문 → 체결 → 보유 + 정합 판정
     */
    @Transactional(readOnly = true)
    fun trace(
        userId: String,
        signalId: Long,
    ): SignalTrace {
        val enriched = signalQueryService.get(userId, signalId)
        val signal = enriched.signal
        val strategyId = signal.strategyId
        val code = signal.code

        // hop 2: 이 사인이 발사한 주문 (STRATEGY_SIGNAL + triggerRefId == signalId)
        val orders = orderRequestAdapter.findBySignalTrigger(userId, signalId)

        // hop 3: 각 주문의 체결 타임라인 (부분체결 N건 시계열)
        val tracedOrders =
            orders.map { order ->
                val orderId = order.id ?: 0L
                val executions =
                    if (orderId > 0L) orderExecutionAdapter.findByOrderId(orderId) else emptyList()
                TracedOrder(order = order, executions = executions)
            }

        // hop 4: 현재 그 전략이 이 종목 보유 (user+strategy+code 단건)
        val strategyPosition =
            strategyPositionReadJpaRepository.findByUserIdAndStrategyIdAndCode(userId, strategyId, code)

        // 검산: KIS 권위(user+code 합산) vs 이 전략 귀속 — 정합 badge 산정
        val authorityQty = positionJpaRepository.sumQtyByUserIdAndCode(userId, code)
        val strategyQty = strategyPosition?.quantity ?: 0

        log.debug(
            "시그널 추적: userId={} signalId={} strategyId={} code={} orders={} strategyQty={} authorityQty={}",
            userId,
            signalId,
            strategyId,
            code,
            orders.size,
            strategyQty,
            authorityQty,
        )

        return SignalTrace(
            enriched = enriched,
            orders = tracedOrders,
            strategyPosition = strategyPosition,
            strategyHeldQty = strategyQty,
            authorityHeldQty = authorityQty.toInt(),
        )
    }
}

/** 주문 + 체결 타임라인 묶음 (1 주문 : N 부분체결). */
data class TracedOrder(
    val order: OrderRequest,
    val executions: List<OrderExecution>,
)

/**
 * 시그널 키 사슬 추적 결과.
 *
 * @property enriched 사인(전략명·조건 enrich)
 * @property orders 이 사인이 발사한 주문 + 각 주문 체결 타임라인
 * @property strategyPosition 현재 전략 보유 (없으면 null = 보유 0)
 * @property strategyHeldQty 전략 귀속 보유 수량 (strategy_position.quantity)
 * @property authorityHeldQty KIS 권위 보유 수량 (position user+code 합산) — 정합 검산 기준
 */
data class SignalTrace(
    val enriched: EnrichedSignal,
    val orders: List<TracedOrder>,
    val strategyPosition: StrategyPositionReadJpaEntity?,
    val strategyHeldQty: Int,
    val authorityHeldQty: Int,
)
