package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 체결 row 저장과 내부 position projection 반영을 같은 transaction boundary로 묶는다.
 *
 * position 반영 실패 후 execution row만 남으면 다음 WS/backfill dedup에서 이미 처리된 체결로 오판하므로,
 * 두 작업은 함께 성공하거나 함께 롤백되어야 한다.
 *
 * ## 전략별 포지션 귀속 (ADR 0110, 분할매수 R2-2)
 * KIS 잔고 권위 position(executionPositionUpdatePort) 반영 직후, **전략 lot ledger**(strategy_position)를
 * 동반 upsert한다([ExecutionStrategyPositionUpdatePort]). strategy_position 은 **2차 ledger**(KIS·position 이
 * 상위 권위 — spec §3-1)이므로, 그 갱신 실패가 권위 write(execution·position)를 롤백하지 않도록
 * runCatching 으로 격리한다(graceful). bean 부재(어댑터 미합류) 시에도 현행 position 흐름 무손상.
 */
@Component
class ExecutionPersistenceService(
    private val orderExecutionRepository: OrderExecutionRepository,
    private val executionPositionUpdatePort: ExecutionPositionUpdatePort,
    private val executionStrategyPositionUpdatePortProvider: ObjectProvider<ExecutionStrategyPositionUpdatePort>,
) {
    private val log = LoggerFactory.getLogger(ExecutionPersistenceService::class.java)

    @Transactional
    fun saveAndApplyPosition(
        request: OrderRequest,
        execution: OrderExecution,
    ): OrderExecution {
        val saved = orderExecutionRepository.save(execution)
        executionPositionUpdatePort.applyExecution(request, saved)
        applyStrategyPosition(request, saved)
        return saved
    }

    /**
     * 전략 lot ledger 동반 upsert — 2차 ledger 이므로 실패가 권위 write 를 롤백하지 않도록 격리.
     * (drift 는 어댑터가 WARN SystemEvent 로 보고만 — 보정 0, KIS/position 권위 우선)
     */
    private fun applyStrategyPosition(
        request: OrderRequest,
        execution: OrderExecution,
    ) {
        val port = executionStrategyPositionUpdatePortProvider.ifAvailable ?: return
        runCatching { port.applyExecution(request, execution) }
            .onFailure { e ->
                log.warn(
                    "strategy_position 갱신 실패 — 권위 position write 는 유지(2차 ledger 격리): orderId={} strategyId={} err={}",
                    execution.orderId,
                    request.strategyId,
                    e.message,
                )
            }
    }
}
