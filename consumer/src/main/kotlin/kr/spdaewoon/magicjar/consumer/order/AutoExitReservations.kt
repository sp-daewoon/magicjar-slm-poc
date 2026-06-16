package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource

/**
 * 자가 관리 청산 선주문(auto-tp/auto-sl) 예약 분류 공용 유틸 (DRY · 자본 path hotfix #789 후속).
 *
 * ## 왜 한 곳에 (중복 구현 금지 — 진보 페르소나)
 * "매도 시그널 발행 직전 [RestingSellOrderCanceller]가 선취소하는 SELL은 예약 수량에서 빼야 한다"는 동일 규칙이
 * 두 가드에서 필요하다:
 * - [kr.spdaewoon.magicjar.consumer.risk.RiskGatewayEvaluator] — RiskContext.openOrders 기준 가용 매도 수량.
 * - [kr.spdaewoon.magicjar.consumer.risk.SignalGeneratedKafkaListener.ensureKisHoldingIfSell] — KIS 총보유 기준 가용 매도 수량.
 *
 * 두 곳이 같은 분류 규칙을 재구현하면 한쪽만 고쳐져 가드가 어긋난다. 본 유틸이 단일 출처(SoT)다.
 *
 * ## 규칙
 * - `auto-tp:`/`auto-sl:` prefix + 같은 전략 AUTO open SELL → **취소 가능 자가 청산**(예약 제외 대상).
 *   발행 직전 [RestingSellOrderCanceller]가 KIS 취소해 보유 수량을 풀어주므로 가용 매도 수량을 잠그지 않는다.
 * - 수동([OrderSource.MANUAL])/타전략 open SELL → **그대로 예약 차감 유지**(우리가 취소 대상 아님 — 침범 금지).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 순수 유틸 (ADR 0023 §2-4)
 */
internal object AutoExitReservations {
    /** 같은 전략 AUTO 자가 관리 청산 선주문(auto-tp/auto-sl) — 발행 직전 우리가 취소하므로 예약 제외 대상. */
    fun OrderRequest.isCancellableAutoExit(strategyId: Long): Boolean =
        source == OrderSource.AUTO &&
            this.strategyId == strategyId &&
            (
                idempotencyKey.startsWith("${AutoOrderIdempotencyKeys.PREFIX_TAKE_PROFIT}:") ||
                    idempotencyKey.startsWith("${AutoOrderIdempotencyKeys.PREFIX_STOP_LOSS}:")
            )

    /**
     * 취소 불가(수동/타전략) open SELL 예약 수량 합 — 같은 종목 한정.
     *
     * 자가 관리 auto-tp/auto-sl(취소 가능)은 0으로 차감하고, 수동/타전략 SELL만 합산한다.
     * caller가 총보유에서 이 값을 빼면 "선취소로 풀어줄 분은 다시 사용 가능 + 침범 불가 분만 잠금"이 된다.
     */
    fun foreignReservedSellQuantity(
        openOrders: List<OrderRequest>,
        code: String,
        strategyId: Long,
    ): Int =
        openOrders
            .filter { it.code == code && it.side == OrderSide.SELL }
            .filterNot { it.isCancellableAutoExit(strategyId) }
            .sumOf { it.quantity }
}
