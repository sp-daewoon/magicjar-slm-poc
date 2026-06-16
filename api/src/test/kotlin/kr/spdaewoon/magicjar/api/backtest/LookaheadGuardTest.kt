package kr.spdaewoon.magicjar.api.backtest

import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.backtest.LookaheadGuard
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * LookaheadGuard 단위 테스트 (ADR 0056 §2-2-B + §2-2-C negative case 5건).
 *
 * 검증 1 (negative): t=10 시점 evaluator에서 t+1 캔들 접근 시도 → IllegalStateException
 * 검증 2 (negative): allCandles 크기 부족 시 → IllegalStateException
 * 검증 3 (negative): currentIndex 음수 → IllegalArgumentException
 * 검증 4 (negative): currentIndex >= allCandles.size → IllegalArgumentException
 * 검증 5 (positive): safeSlice 정상 동작 — slice.size == currentIndex + 1
 * 검증 6 (positive): guardSlice 정상 통과
 * 검증 7 (negative): slice 크기 초과 → IllegalStateException
 */
class LookaheadGuardTest {
    private fun makeCandles(count: Int): List<Candle> {
        val now = Clock.System.now()
        return (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = kotlinx.datetime.Instant.fromEpochSeconds(now.epochSeconds + i * 86400),
                openRaw = BigDecimal("100"),
                highRaw = BigDecimal("110"),
                lowRaw = BigDecimal("90"),
                closeRaw = BigDecimal("100"),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1000L,
                source = "TEST",
            )
        }
    }

    @Test
    fun `검증1_negative_slice에 t+1 캔들 포함 시 IllegalStateException`() {
        val allCandles = makeCandles(20)
        val t = 10 // currentIndex = 10, 허용 최대 크기 = 11
        val illegalSlice = allCandles.subList(0, t + 2) // 크기 12 — 룩어헤드 포함

        assertThrows(IllegalStateException::class.java) {
            LookaheadGuard.guardSlice(illegalSlice, allCandles, t)
        }
    }

    @Test
    fun `검증2_negative_allCandles 크기 부족 시 IllegalStateException`() {
        val allCandles = makeCandles(5)
        val slice = makeCandles(5)
        // currentIndex = 5이면 allCandles.size < maxAllowedSize
        assertThrows(IllegalStateException::class.java) {
            LookaheadGuard.guardSlice(slice, allCandles, 10)
        }
    }

    @Test
    fun `검증3_negative_currentIndex 음수 → IllegalArgumentException`() {
        val allCandles = makeCandles(10)
        assertThrows(IllegalArgumentException::class.java) {
            LookaheadGuard.safeSlice(allCandles, -1)
        }
    }

    @Test
    fun `검증4_negative_currentIndex는_allCandles_size보다_크면_IllegalArgumentException`() {
        val allCandles = makeCandles(10)
        assertThrows(IllegalArgumentException::class.java) {
            LookaheadGuard.safeSlice(allCandles, 10) // 0-based index 10이면 11번째이므로 범위 초과
        }
    }

    @Test
    fun `검증5_positive_safeSlice 크기 = currentIndex + 1`() {
        val allCandles = makeCandles(20)
        val t = 9
        val slice = LookaheadGuard.safeSlice(allCandles, t)
        assertEquals(t + 1, slice.size)
    }

    @Test
    fun `검증6_positive_guardSlice 정상 통과`() {
        val allCandles = makeCandles(20)
        val t = 10
        val safeSlice = LookaheadGuard.safeSlice(allCandles, t)
        // 예외 없이 통과해야 함
        LookaheadGuard.guardSlice(safeSlice, allCandles, t)
    }

    @Test
    fun `검증7_negative_slice 크기 = t+2 → IllegalStateException`() {
        val allCandles = makeCandles(20)
        val t = 5
        val oversizedSlice = allCandles.subList(0, t + 2) // 크기 7 > 허용 6

        assertThrows(IllegalStateException::class.java) {
            LookaheadGuard.guardSlice(oversizedSlice, allCandles, t)
        }
    }

    @Test
    fun `t=0 시점에서 slice 크기 1이어야 한다`() {
        val allCandles = makeCandles(10)
        val slice = LookaheadGuard.safeSlice(allCandles, 0)
        assertEquals(1, slice.size)
    }
}
