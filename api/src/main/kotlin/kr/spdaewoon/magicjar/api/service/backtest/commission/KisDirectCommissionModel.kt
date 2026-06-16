package kr.spdaewoon.magicjar.api.service.backtest.commission

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestCommissionModel
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * KIS 다이렉트 위탁 수수료 모델 (ADR 0056 §2-3-A).
 *
 * ### raw 출처 (자체 추론 0건 — 모든 수수료율은 아래 raw 인용)
 * - 위탁 수수료 0.015% (KIS 다이렉트 기준):
 *   KIS https://securities.koreainvestment.com (수수료 안내)
 * - 유관기관 수수료 0.0036% (KRX·KSD·증선위 합산):
 *   KRX · KSD (한국예탁결제원) · 금융위원회 증선위 합산 기준
 * - VAT 10% (수수료 합계에 부과):
 *   부가가치세법 (국세청 https://www.nts.go.kr)
 *
 * ### 계산식
 * commission = (notional × (위탁 0.015% + 유관기관 0.0036%)) × 1.10 (VAT)
 */
@Component
class KisDirectCommissionModel : BacktestCommissionModel {
    override val name: String = "kis_direct"

    companion object {
        /** KIS 다이렉트 위탁 수수료율 0.015% — 출처: KIS */
        private val BROKERAGE_RATE = BigDecimal("0.00015")

        /** 유관기관 수수료율 0.0036% — 출처: KRX·KSD·증선위 합산 */
        private val EXCHANGE_FEE_RATE = BigDecimal("0.000036")

        /** VAT 10% */
        private val VAT_RATE = BigDecimal("0.10")

        private val ONE = BigDecimal.ONE
    }

    /**
     * 수수료를 계산한다 (매수·매도 모두 부과).
     *
     * 계산: notional × (위탁 + 유관기관) × (1 + VAT)
     */
    override fun calculate(
        side: TradeSide,
        notional: BigDecimal,
    ): BigDecimal {
        val baseRate = BROKERAGE_RATE.add(EXCHANGE_FEE_RATE)
        val baseCommission = notional.multiply(baseRate)
        val total = baseCommission.multiply(ONE.add(VAT_RATE))
        return total.setScale(0, RoundingMode.HALF_UP)
    }
}
