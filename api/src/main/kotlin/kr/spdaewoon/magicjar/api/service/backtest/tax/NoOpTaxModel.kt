package kr.spdaewoon.magicjar.api.service.backtest.tax

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestTaxModel
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 세금 없음 모델 — 테스트 및 순수 전략 성과 측정 용도 (ADR 0056 §2-3-B).
 *
 * 모든 거래에서 세금 0 반환.
 */
@Component
class NoOpTaxModel : BacktestTaxModel {
    override val name: String = "none"

    override fun calculate(
        side: TradeSide,
        price: BigDecimal,
        quantity: Long,
        market: String,
    ): BigDecimal = BigDecimal.ZERO
}
