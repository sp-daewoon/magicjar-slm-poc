package kr.spdaewoon.magicjar.api.service.backtest.slippage

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestSlippageModel
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 슬리피지 없음 모델 — 테스트 및 이론적 성과 측정 용도 (ADR 0056 §2-3-B).
 */
@Component
class NoOpSlippageModel : BacktestSlippageModel {
    override val name: String = "none"

    override fun apply(
        side: TradeSide,
        rawPrice: BigDecimal,
    ): BigDecimal = rawPrice
}
