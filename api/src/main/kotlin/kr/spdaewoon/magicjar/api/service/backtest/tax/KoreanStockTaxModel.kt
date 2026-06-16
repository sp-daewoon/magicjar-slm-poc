package kr.spdaewoon.magicjar.api.service.backtest.tax

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestTaxModel
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 한국 주식 세금 모델 (ADR 0056 §2-3-A).
 *
 * ### raw 출처 (자체 추론 0건 — 모든 세율은 아래 raw 인용)
 * - 증권거래세 0.18% (KOSPI·KOSDAQ 매도 시):
 *   국세청 · KRX https://www.krx.co.kr (거래수수료·세금 안내)
 * - 농어촌특별세 0.15% (KOSPI 매도 시 한정):
 *   국세청 https://www.nts.go.kr (농어촌특별세법 §3)
 * - VAT 10% (수수료 위에 부과 — 세금 자체엔 부과 안 됨):
 *   부가가치세법 (국세청 https://www.nts.go.kr)
 *
 * ### 주의
 * - 매수(BUY) 시 세금 0 (증권거래세는 매도자 부담)
 * - 양도소득세 1차 모델 0% (개인 비대주주 가정 — 후속 ADR 0082+에서 대주주 분기 검토)
 * - KOSDAQ 농특세 0 (KOSPI 한정)
 */
@Component
class KoreanStockTaxModel : BacktestTaxModel {
    override val name: String = "korean_default"

    companion object {
        /** 증권거래세율 0.18% — 출처: 국세청·KRX */
        private val SECURITIES_TRANSACTION_TAX_RATE = BigDecimal("0.0018")

        /** 농어촌특별세율 0.15% — KOSPI 한정. 출처: 국세청 농어촌특별세법 §3 */
        private val AGRICULTURAL_SPECIAL_TAX_RATE = BigDecimal("0.0015")

        private const val KOSPI = "KOSPI"
    }

    /**
     * 세금을 계산한다.
     *
     * 매도(SELL) 시: 증권거래세 + 농특세(KOSPI) 부과.
     * 매수(BUY) 시: 0 (세금 없음).
     */
    override fun calculate(
        side: TradeSide,
        price: BigDecimal,
        quantity: Long,
        market: String,
    ): BigDecimal {
        if (side == TradeSide.BUY) return BigDecimal.ZERO

        val notional = price.multiply(BigDecimal(quantity))

        // 증권거래세
        val transactionTax = notional.multiply(SECURITIES_TRANSACTION_TAX_RATE)

        // 농어촌특별세 — KOSPI 한정
        val agriculturalTax =
            if (market.uppercase() == KOSPI) {
                notional.multiply(AGRICULTURAL_SPECIAL_TAX_RATE)
            } else {
                BigDecimal.ZERO
            }

        return transactionTax.add(agriculturalTax).setScale(0, RoundingMode.HALF_UP)
    }
}
