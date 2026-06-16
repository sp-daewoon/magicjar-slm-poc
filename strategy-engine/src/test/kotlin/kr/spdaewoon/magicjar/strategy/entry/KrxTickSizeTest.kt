package kr.spdaewoon.magicjar.strategy.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [KrxTickSize] 단위 테스트 — 2023-01-25 KRX 7단계 호가가격단위 (ADR 0114 §6).
 *
 * 라운딩 방향: marketable BUY 상한·SELL LIMIT은 모두 **내림(FLOOR)**(자본 보호).
 */
class KrxTickSizeTest {
    private fun tick(price: String) = KrxTickSize.tickSizeFor(BigDecimal(price))

    @Test
    fun `tick size bands cover 7 KRX tiers`() {
        assertEquals(BigDecimal("1"), tick("1500")) // < 2000 → 1
        assertEquals(BigDecimal("5"), tick("2000")) // [2000,5000) → 5 (경계 포함)
        assertEquals(BigDecimal("5"), tick("4999"))
        assertEquals(BigDecimal("10"), tick("5000")) // [5000,20000) → 10
        assertEquals(BigDecimal("10"), tick("19999"))
        assertEquals(BigDecimal("50"), tick("20000")) // [20000,50000) → 50
        assertEquals(BigDecimal("50"), tick("49999"))
        assertEquals(BigDecimal("100"), tick("50000")) // [50000,200000) → 100
        assertEquals(BigDecimal("100"), tick("199999"))
        assertEquals(BigDecimal("500"), tick("200000")) // [200000,500000) → 500
        assertEquals(BigDecimal("500"), tick("499999"))
        assertEquals(BigDecimal("1000"), tick("500000")) // >= 500000 → 1000
        assertEquals(BigDecimal("1000"), tick("1250000"))
    }

    @Test
    fun `floorToTick aligns within band`() {
        assertEquals(BigDecimal("1503"), KrxTickSize.floorToTick(BigDecimal("1503"))) // 1원 단위 그대로
        assertEquals(BigDecimal("4995"), KrxTickSize.floorToTick(BigDecimal("4998"))) // 5원 단위 floor
        assertEquals(BigDecimal("10010"), KrxTickSize.floorToTick(BigDecimal("10015"))) // 10원 단위
        assertEquals(BigDecimal("49950"), KrxTickSize.floorToTick(BigDecimal("49980"))) // 50원 단위
    }

    @Test
    fun `floorToTick boundary correction — price just below band edge uses lower band tick`() {
        // 49,999 은 50원 단위 구간(20000~49999). 100원 단위로 내리면 49900으로 과하향 → 49950이 맞다.
        assertEquals(BigDecimal("49950"), KrxTickSize.floorToTick(BigDecimal("49999")))
        // 19,999 은 10원 단위 → 19990
        assertEquals(BigDecimal("19990"), KrxTickSize.floorToTick(BigDecimal("19999")))
        // 199,999 은 100원 단위 → 199900
        assertEquals(BigDecimal("199900"), KrxTickSize.floorToTick(BigDecimal("199999")))
    }

    @Test
    fun `floorToTick exact tick multiple unchanged`() {
        assertEquals(BigDecimal("50000"), KrxTickSize.floorToTick(BigDecimal("50000")))
        assertEquals(BigDecimal("500000"), KrxTickSize.floorToTick(BigDecimal("500000")))
    }

    @Test
    fun `floorToTick zero stays zero`() {
        assertEquals(BigDecimal.ZERO, KrxTickSize.floorToTick(BigDecimal.ZERO))
    }
}
