package kr.spdaewoon.magicjar.api.backtest

import kr.spdaewoon.magicjar.api.service.backtest.commission.KisDirectCommissionModel
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KisDirectCommissionModel 단위 테스트 (ADR 0056 §2-3-A — raw 출처 정합).
 *
 * 검증 1: 모델 이름
 * 검증 2: BUY 수수료 — (위탁 0.015% + 유관기관 0.0036%) × 1.10 VAT
 * 검증 3: SELL 수수료 — BUY와 동일 (매수·매도 동일 적용)
 * 검증 4: 수수료 계산 정확성 — 1,000,000원 거래 대금 기준
 * 검증 5: 소수 거래 대금 계산
 */
class KisDirectCommissionModelTest {
    private val model = KisDirectCommissionModel()

    @Test
    fun `모델 이름은 kis_direct`() {
        assertEquals("kis_direct", model.name)
    }

    @Test
    fun `BUY 수수료 계산 — 위탁 0_015% + 유관기관 0_0036% × VAT 1_10`() {
        // 1,000,000원 기준
        // 위탁: 1,000,000 × 0.00015 = 150
        // 유관기관: 1,000,000 × 0.000036 = 36
        // 소계: 186
        // VAT: 186 × 1.10 = 204.6 → 반올림 205
        val commission = model.calculate(TradeSide.BUY, BigDecimal("1000000"))
        assertEquals(BigDecimal("205"), commission)
    }

    @Test
    fun `SELL 수수료 계산 — BUY와 동일`() {
        val buyCommission = model.calculate(TradeSide.BUY, BigDecimal("1000000"))
        val sellCommission = model.calculate(TradeSide.SELL, BigDecimal("1000000"))
        assertEquals(buyCommission, sellCommission)
    }

    @Test
    fun `소액 거래 수수료 계산`() {
        // 100,000원
        // (0.00015 + 0.000036) × 100,000 = 18.6 → × 1.10 = 20.46 → 반올림 20
        val commission = model.calculate(TradeSide.BUY, BigDecimal("100000"))
        assertEquals(BigDecimal("20"), commission)
    }

    @Test
    fun `대규모 거래 수수료 계산`() {
        // 10,000,000원
        // (0.00015 + 0.000036) × 10,000,000 = 1,860 → × 1.10 = 2,046
        val commission = model.calculate(TradeSide.BUY, BigDecimal("10000000"))
        assertEquals(BigDecimal("2046"), commission)
    }
}
