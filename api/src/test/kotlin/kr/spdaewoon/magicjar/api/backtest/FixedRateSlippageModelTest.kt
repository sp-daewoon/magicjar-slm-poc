package kr.spdaewoon.magicjar.api.backtest

import kr.spdaewoon.magicjar.api.service.backtest.slippage.FixedRateSlippageModel
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * FixedRateSlippageModel 단위 테스트 (ADR 0056 §2-3-A — magicJar own).
 *
 * 검증 1: 모델 이름
 * 검증 2: BUY — rawPrice × (1 + 0.0005)
 * 검증 3: SELL — rawPrice × (1 - 0.0005)
 * 검증 4: BUY 체결가 > 원가
 * 검증 5: SELL 체결가 < 원가
 */
class FixedRateSlippageModelTest {
    private val model = FixedRateSlippageModel()

    @Test
    fun `모델 이름은 fixed_0_05`() {
        assertEquals("fixed_0.05", model.name)
    }

    @Test
    fun `BUY — rawPrice × (1 + 0_0005)`() {
        // 100,000 × 1.0005 = 100,050
        val price = model.apply(TradeSide.BUY, BigDecimal("100000"))
        assertEquals(BigDecimal("100050"), price)
    }

    @Test
    fun `SELL — rawPrice × (1 - 0_0005)`() {
        // 100,000 × 0.9995 = 99,950
        val price = model.apply(TradeSide.SELL, BigDecimal("100000"))
        assertEquals(BigDecimal("99950"), price)
    }

    @Test
    fun `BUY 체결가는 원가보다 높아야 한다`() {
        val raw = BigDecimal("70000")
        val executed = model.apply(TradeSide.BUY, raw)
        assertTrue(executed > raw, "BUY executed=$executed should be > raw=$raw")
    }

    @Test
    fun `SELL 체결가는 원가보다 낮아야 한다`() {
        val raw = BigDecimal("70000")
        val executed = model.apply(TradeSide.SELL, raw)
        assertTrue(executed < raw, "SELL executed=$executed should be < raw=$raw")
    }

    @Test
    fun `소수점 단가 처리`() {
        // 70,350 × 1.0005 = 70,385.175 → 소수점 0 자리로 반올림
        val price = model.apply(TradeSide.BUY, BigDecimal("70350"))
        // scale 0이면 70385
        assertEquals(0, price.scale())
    }
}
