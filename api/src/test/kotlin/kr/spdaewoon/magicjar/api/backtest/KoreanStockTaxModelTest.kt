package kr.spdaewoon.magicjar.api.backtest

import kr.spdaewoon.magicjar.api.service.backtest.tax.KoreanStockTaxModel
import kr.spdaewoon.magicjar.domain.backtest.TradeSide
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * KoreanStockTaxModel 단위 테스트 (ADR 0056 §2-3-A — raw 출처 정합).
 *
 * 검증 1: SELL KOSPI — 증권거래세 0.18% + 농특세 0.15% = 0.33%
 * 검증 2: SELL KOSDAQ — 증권거래세 0.18%만 (농특세 0)
 * 검증 3: BUY — 세금 0
 * 검증 4: 가격 × 수량 × 세율 계산 정확성
 * 검증 5: 대소문자 무시 (kospi → KOSPI)
 */
class KoreanStockTaxModelTest {
    private val model = KoreanStockTaxModel()

    @Test
    fun `모델 이름은 korean_default`() {
        assertEquals("korean_default", model.name)
    }

    @Test
    fun `SELL KOSPI — 증권거래세 0_18% + 농특세 0_15% = 0_33%`() {
        // 100,000원 × 10주 = 1,000,000원
        // 거래세 1,000,000 × 0.0018 = 1,800
        // 농특세 1,000,000 × 0.0015 = 1,500
        // 합계 = 3,300
        val tax = model.calculate(TradeSide.SELL, BigDecimal("100000"), 10, "KOSPI")
        assertEquals(BigDecimal("3300"), tax)
    }

    @Test
    fun `SELL KOSDAQ — 증권거래세 0_18%만 (농특세 0)`() {
        // 100,000원 × 10주 = 1,000,000원
        // 거래세 1,000,000 × 0.0018 = 1,800
        val tax = model.calculate(TradeSide.SELL, BigDecimal("100000"), 10, "KOSDAQ")
        assertEquals(BigDecimal("1800"), tax)
    }

    @Test
    fun `BUY — 세금 0`() {
        val tax = model.calculate(TradeSide.BUY, BigDecimal("100000"), 10, "KOSPI")
        assertEquals(BigDecimal.ZERO, tax)
    }

    @Test
    fun `소수 단가 계산 정확성`() {
        // 70350원 × 5주 = 351,750원
        // 거래세 351,750 × 0.0018 = 633.15 → 반올림 633
        // 농특세 351,750 × 0.0015 = 527.625 → 반올림 528
        // 합계 = 1,161
        val tax = model.calculate(TradeSide.SELL, BigDecimal("70350"), 5, "KOSPI")
        assertEquals(BigDecimal("1161"), tax)
    }

    @Test
    fun `대소문자 무시 — kospi와 KOSPI 동일 처리`() {
        val taxLower = model.calculate(TradeSide.SELL, BigDecimal("100000"), 1, "kospi")
        val taxUpper = model.calculate(TradeSide.SELL, BigDecimal("100000"), 1, "KOSPI")
        assertEquals(taxUpper, taxLower)
    }
}
