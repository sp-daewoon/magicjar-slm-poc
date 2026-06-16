package kr.spdaewoon.magicjar.api.service.backtest.commission

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCommissionModel
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 수수료 없음 모델 — 테스트 및 순수 전략 성과 측정 용도 (ADR 0056 §2-3-B).
 */
@Component
class NoOpCommissionModel : BacktestCommissionModel {
    override val name: String = "none"

    override fun calculate(
        side: TradeSide,
        notional: BigDecimal,
    ): BigDecimal = BigDecimal.ZERO
}
