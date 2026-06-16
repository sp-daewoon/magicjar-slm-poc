package kr.spdaewoon.magicjar.api.application.screener

import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * MultiCriteriaRanker 단위 테스트 (ADR 0071 §2-5 E-1 — fixed weights 4 기준).
 *
 * 검증 시나리오 (spec §11-1):
 * 1. fixed weights 가중치 정합
 * 2. descending score 정렬
 * 3. 동점 처리 — instrumentCode 사전순 stable
 * 4. limit cut + rank 1-base 부여
 * 5. empty matches → empty result
 */
class MultiCriteriaRankerTest {
    private val ranker = MultiCriteriaRanker()

    @Test
    fun `4 기준 fixed weights 가중치 정합 — signal 0_40 + volume 0_30 + atr 0_20 + cap 0_10`() {
        // given — 모든 기준 1.0
        val match =
            match(
                code = "005930",
                signal = bd("1.0"),
                volume = bd("1.0"),
                atr = bd("1.0"),
                cap = bd("1.0"),
            )

        // when
        val result = ranker.rank(listOf(match), limit = 10)

        // then — 1.0 * (0.40 + 0.30 + 0.20 + 0.10) = 1.0
        assertEquals(1, result.size)
        val top = result[0]
        assertEquals(0, top.score.compareTo(bd("1.000000")), "score=${top.score}")
        assertEquals(0, top.breakdown.total.compareTo(bd("1.000000")), "total=${top.breakdown.total}")
        assertEquals(1, top.rank)
    }

    @Test
    fun `weighted sum 부분 점수 정합 — signal만 1_0이면 total 0_40`() {
        val match =
            match(
                code = "005930",
                signal = bd("1.0"),
                volume = bd("0.0"),
                atr = bd("0.0"),
                cap = bd("0.0"),
            )

        val result = ranker.rank(listOf(match), limit = 10)

        assertEquals(1, result.size)
        // 1.0 * 0.40 = 0.40
        assertEquals(0, result[0].score.compareTo(bd("0.400000")))
    }

    @Test
    fun `descending score 정렬 — 가장 높은 score 가 rank 1`() {
        val low =
            match(
                code = "111111",
                signal = bd("0.2"),
                volume = bd("0.2"),
                atr = bd("0.2"),
                cap = bd("0.2"),
            )
        val high =
            match(
                code = "999999",
                signal = bd("0.9"),
                volume = bd("0.9"),
                atr = bd("0.9"),
                cap = bd("0.9"),
            )
        val mid =
            match(
                code = "555555",
                signal = bd("0.5"),
                volume = bd("0.5"),
                atr = bd("0.5"),
                cap = bd("0.5"),
            )

        val result = ranker.rank(listOf(low, high, mid), limit = 10)

        assertEquals(3, result.size)
        assertEquals("999999", result[0].instrumentCode)
        assertEquals(1, result[0].rank)
        assertEquals("555555", result[1].instrumentCode)
        assertEquals(2, result[1].rank)
        assertEquals("111111", result[2].instrumentCode)
        assertEquals(3, result[2].rank)
    }

    @Test
    fun `동점 처리 — instrumentCode 사전순 stable sort (재현 결정성)`() {
        val a =
            match(
                code = "222222",
                signal = bd("0.5"),
                volume = bd("0.5"),
                atr = bd("0.5"),
                cap = bd("0.5"),
            )
        val b =
            match(
                code = "111111",
                signal = bd("0.5"),
                volume = bd("0.5"),
                atr = bd("0.5"),
                cap = bd("0.5"),
            )
        val c =
            match(
                code = "333333",
                signal = bd("0.5"),
                volume = bd("0.5"),
                atr = bd("0.5"),
                cap = bd("0.5"),
            )

        val result = ranker.rank(listOf(a, b, c), limit = 10)

        // 모두 동점 → 사전순 stable
        assertEquals(3, result.size)
        assertEquals("111111", result[0].instrumentCode)
        assertEquals("222222", result[1].instrumentCode)
        assertEquals("333333", result[2].instrumentCode)
    }

    @Test
    fun `limit cut + rank 1-base — limit 2 면 상위 2건만 반환`() {
        val high =
            match(
                code = "999999",
                signal = bd("0.9"),
                volume = bd("0.9"),
                atr = bd("0.9"),
                cap = bd("0.9"),
            )
        val mid =
            match(
                code = "555555",
                signal = bd("0.5"),
                volume = bd("0.5"),
                atr = bd("0.5"),
                cap = bd("0.5"),
            )
        val low =
            match(
                code = "111111",
                signal = bd("0.1"),
                volume = bd("0.1"),
                atr = bd("0.1"),
                cap = bd("0.1"),
            )

        val result = ranker.rank(listOf(high, mid, low), limit = 2)

        assertEquals(2, result.size)
        assertEquals("999999", result[0].instrumentCode)
        assertEquals("555555", result[1].instrumentCode)
        // low는 cut됨
        assertTrue(result.none { it.instrumentCode == "111111" })
    }

    @Test
    fun `empty matches — empty result`() {
        val result = ranker.rank(emptyList(), limit = 10)
        assertEquals(0, result.size)
    }

    @Test
    fun `outlier 처리 — signal 0_0 + 나머지 1_0 = 0_60 정합`() {
        val match =
            match(
                code = "005930",
                signal = bd("0.0"),
                volume = bd("1.0"),
                atr = bd("1.0"),
                cap = bd("1.0"),
            )

        val result = ranker.rank(listOf(match), limit = 10)

        // 0 * 0.40 + 1.0 * 0.30 + 1.0 * 0.20 + 1.0 * 0.10 = 0.60
        assertEquals(0, result[0].score.compareTo(bd("0.600000")))
    }

    // ────────────────────────────────────────────────────────────────────────
    // helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun match(
        code: String,
        signal: BigDecimal,
        volume: BigDecimal,
        atr: BigDecimal,
        cap: BigDecimal,
    ): ScreenerMatch =
        ScreenerMatch(
            instrumentCode = code,
            instrumentName = "test-$code",
            rank = 0,
            score = BigDecimal.ZERO,
            breakdown =
                ScreenerScoreBreakdown(
                    signal = signal,
                    volume = volume,
                    atr = atr,
                    cap = cap,
                    total = BigDecimal.ZERO,
                ),
            reason = "test",
        )

    private fun bd(v: String): BigDecimal = BigDecimal(v)
}
