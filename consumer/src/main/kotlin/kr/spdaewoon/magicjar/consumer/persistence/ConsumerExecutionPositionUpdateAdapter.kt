package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionPositionUpdatePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 체결 직후 position upsert 어댑터.
 *
 * KIS 잔고 reconcile이 최종 신뢰원이지만, 자동매매 리스크 평가는 다음 신호 전에 내부 position이
 * 최신이어야 하므로 WS/backfill 체결 저장 직후 같은 transaction boundary에서 낙관 갱신한다.
 */
@Component
class ConsumerExecutionPositionUpdateAdapter(
    private val positionJpaRepository: PositionJpaRepository,
) : ExecutionPositionUpdatePort {
    private val log = LoggerFactory.getLogger(ConsumerExecutionPositionUpdateAdapter::class.java)

    @Transactional
    override fun applyExecution(
        request: OrderRequest,
        execution: OrderExecution,
    ) {
        val source = request.mode.name
        val current = positionJpaRepository.findByUserIdAndCodeAndSource(request.userId, request.code, source)
        val currentQty = current?.quantity ?: 0
        val currentAvg = current?.avgPrice ?: BigDecimal.ZERO
        val fillQty = execution.executedQuantity.coerceAtLeast(0)
        if (fillQty == 0) return

        val next =
            when (request.side) {
                OrderSide.BUY -> {
                    val nextQty = currentQty + fillQty
                    val currentNotional = currentAvg.multiply(BigDecimal(currentQty))
                    val fillNotional = execution.executedPrice.multiply(BigDecimal(fillQty))
                    val nextAvg =
                        currentNotional
                            .add(fillNotional)
                            .divide(BigDecimal(nextQty), 4, RoundingMode.HALF_UP)
                    nextQty to nextAvg
                }
                OrderSide.SELL -> {
                    val nextQty = (currentQty - fillQty).coerceAtLeast(0)
                    if (fillQty > currentQty) {
                        log.warn(
                            "execution position update SELL overflow: userId={} code={} currentQty={} fillQty={}",
                            maskUserId(request.userId),
                            request.code,
                            currentQty,
                            fillQty,
                        )
                    }
                    nextQty to if (nextQty == 0) BigDecimal.ZERO else currentAvg
                }
            }

        positionJpaRepository.upsertReconciled(
            userId = request.userId,
            code = request.code,
            quantity = next.first,
            avgPrice = next.second,
            source = source,
        )
        log.info(
            "execution position update: userId={} code={} side={} fillQty={} nextQty={} avgPrice={} source={}",
            maskUserId(request.userId),
            request.code,
            request.side,
            fillQty,
            next.first,
            next.second.toPlainString(),
            source,
        )
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
