package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode

/**
 * KIS REST 주문 어댑터 포트 (ADR 0068 §14-1 LOCK · §14-5 정합).
 *
 * Phase 1 consumer KisOrderHttpAdapter가 구현.
 *
 * submit: TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U (raw L10097~L10213)
 * cancel: TTTC0013U·VTTC0013U                     (raw L10771~L10884)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface KisOrderPort {
    fun submit(
        intent: OrderIntent,
        mode: TradingMode,
    ): OrderResult<DenyReason, KisOrderAck>

    fun cancel(
        request: OrderRequest,
        mode: TradingMode,
    ): OrderResult<DenyReason, Unit>
}
