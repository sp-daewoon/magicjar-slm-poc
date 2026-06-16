package kr.spdaewoon.magicjar.api.service.backtest.slippage

import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestSlippageModel
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 고정 슬리피지 모델 — magicJar own (KIS raw 부재 — 자체 결정) (ADR 0056 §2-3-A).
 *
 * ### Source: magicJar own — KIS raw 미존재
 * KIS·KRX에는 슬리피지 표준 수치 부재.
 * 한국 주식 시장 분봉 평균 spread + 1tick(0.1~0.5%)의 보수적 절반 = 0.05% 채택.
 *
 * ### 결정 사유 (ADR 0056 §3-6 + §8-3 인용)
 * - 매수 시: rawPrice × (1 + 0.0005) — 매수가가 현재 호가보다 높게 체결되는 효과
 * - 매도 시: rawPrice × (1 - 0.0005) — 매도가가 현재 호가보다 낮게 체결되는 효과
 * - 후속 ADR 0081+에서 거래량 기반 동적 모델 검토
 *
 * ### PR-39a ConsecutiveDirectionCalculator 패턴 정합
 * magicJar own 결정 사유 명시 — 자체 추론이 아닌 의도적 설계 결정.
 */
@Component
class FixedRateSlippageModel(
    private val rate: BigDecimal = DEFAULT_RATE,
) : BacktestSlippageModel {
    override val name: String = "fixed_0.05"

    companion object {
        /** 고정 슬리피지 0.05% — magicJar own 결정 */
        val DEFAULT_RATE: BigDecimal = BigDecimal("0.0005")

        private val ONE = BigDecimal.ONE
    }

    /**
     * 슬리피지를 적용한 체결가를 반환한다.
     *
     * - BUY: rawPrice × (1 + rate) — 시장가 매수 시 호가보다 높게 체결
     * - SELL: rawPrice × (1 - rate) — 시장가 매도 시 호가보다 낮게 체결
     */
    override fun apply(
        side: TradeSide,
        rawPrice: BigDecimal,
    ): BigDecimal =
        when (side) {
            TradeSide.BUY -> rawPrice.multiply(ONE.add(rate)).setScale(rawPrice.scale(), RoundingMode.HALF_UP)
            TradeSide.SELL -> rawPrice.multiply(ONE.subtract(rate)).setScale(rawPrice.scale(), RoundingMode.HALF_UP)
        }
}
