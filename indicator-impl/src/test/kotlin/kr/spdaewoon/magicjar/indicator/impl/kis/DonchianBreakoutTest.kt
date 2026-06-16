package kr.spdaewoon.magicjar.indicator.impl.kis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

/**
 * Donchian 채널 돌파 결함(V76 검증 #860) 회귀 가드.
 *
 * 결함: `rollingMax`가 현재 봉을 포함 → `donchian_upper(n)[i] = max(high[0..i] window)`에
 * 현재 봉 high가 들어가 `close[i] > donchian_upper(n)`이 항상 false(close ≤ high ≤ max)로
 * H2 VOLUME_BREAKOUT·H6 RANGE_BREAKOUT이 0매칭. 표준 정의(직전 N봉, 현재 봉 제외)로 fix.
 */
class DonchianBreakoutTest {
    @Test
    fun `donchian_upper는 현재 봉을 제외한 직전 N봉 최고가다 (돌파 비교 성립)`() {
        // 직전 3봉 high = 105·108·110 → max 110. 현재 봉(idx3)이 그 위를 close 113으로 돌파.
        val candles =
            listOf(
                candle(0, 100, 105, 98, 104),
                candle(1, 104, 108, 103, 107),
                candle(2, 107, 110, 106, 109),
                candle(3, 109, 114, 108, 113),
            )

        val result = IndicatorVolatility.donchianUpper(IndicatorSpec("donchian_upper", mapOf("period" to 3)), candles)
        val lastTs = candles.last().ts
        val upperAtLast = result.series.first { it.time == lastTs }.values["upper"]!!

        // 현재 봉(high 114) 제외 → 직전 3봉 최고가 110.
        upperAtLast.compareTo(BigDecimal(110)) shouldBe 0
        // close 113 > donchian_upper(3)=110 → 돌파 성립(결함 전에는 max=114로 절대 불성립).
        (candles.last().closeRaw > upperAtLast) shouldBe true
    }

    @Test
    fun `donchian_lower는 현재 봉을 제외한 직전 N봉 최저가다`() {
        // 직전 3봉 low = 98·103·106 → min 98. 현재 봉 low 95가 그 아래로 이탈.
        val candles =
            listOf(
                candle(0, 100, 105, 98, 104),
                candle(1, 104, 108, 103, 107),
                candle(2, 107, 110, 106, 109),
                candle(3, 100, 101, 95, 96),
            )

        val result = IndicatorVolatility.donchianLower(IndicatorSpec("donchian_lower", mapOf("period" to 3)), candles)
        val lastTs = candles.last().ts
        val lowerAtLast = result.series.first { it.time == lastTs }.values["lower"]!!

        lowerAtLast.compareTo(BigDecimal(98)) shouldBe 0
        (candles.last().lowRaw < lowerAtLast) shouldBe true
    }

    @Test
    fun `현재 봉 신고가는 donchian_upper에 반영되지 않는다 (룩어헤드 미참조)`() {
        // idx2가 직전 어떤 봉보다 높은 high지만, idx2의 donchian_upper(2)는 idx0·idx1만 본다.
        val candles =
            listOf(
                candle(0, 100, 105, 98, 104),
                candle(1, 104, 108, 103, 107),
                candle(2, 107, 200, 106, 199),
            )
        val result = IndicatorVolatility.donchianUpper(IndicatorSpec("donchian_upper", mapOf("period" to 2)), candles)
        val upperAtLast = result.series.first { it.time == candles.last().ts }.values["upper"]!!
        // idx0·idx1 최고가 108 (현재 봉 high 200 무시).
        upperAtLast.compareTo(BigDecimal(108)) shouldBe 0
    }

    @Test
    fun `before(현재봉 포함)는 0매칭, after(현재봉 제외 fix)는 다수 매칭 — 동일 캔들 대조`() {
        // 결정론 랜덤워크 200봉(시드 고정). V76 리포트 §2-2 실측 대조 재현.
        var seed = 42L

        fun rnd(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toDouble() / Int.MAX_VALUE)
        }
        val candles = ArrayList<Candle>(200)
        var price = 10_000.0
        for (i in 0 until 200) {
            val close = price + (rnd() - 0.45) * 200 // 약 상승 편향
            val high = maxOf(price, close) + rnd() * 50
            candles.add(candle(i, price.toLong(), high.toLong(), (minOf(price, close) - 10).toLong(), close.toLong()))
            price = close
        }

        val period = 20
        // AFTER (fix): 실제 donchianUpper = 현재 봉 제외.
        val afterUpper = IndicatorVolatility.donchianUpper(IndicatorSpec("donchian_upper", mapOf("period" to period)), candles)
        val afterByTs = afterUpper.series.associate { it.time to it.values["upper"]!! }
        var afterMatch = 0
        // BEFORE (결함 재현): 현재 봉 포함 rollingMax 직접 계산.
        val inclusive = KisIndicatorMath.rollingMax(KisIndicatorMath.highs(candles), period)
        var beforeMatch = 0
        candles.forEachIndexed { i, c ->
            inclusive[i]?.let { if (c.closeRaw > it) beforeMatch++ }
            afterByTs[c.ts]?.let { if (c.closeRaw > it) afterMatch++ }
        }

        // 결함: 현재 봉 포함이면 close ≤ high ≤ max → 절대 돌파 없음(이 dataset 실측 before=0).
        // fix: 현재 봉 제외면 신고가 돌파 매칭(이 dataset 실측 after=34).
        beforeMatch shouldBe 0
        // fix: 현재 봉 제외면 신고가 돌파가 실제로 매칭(>0).
        (afterMatch > 0) shouldBe true
    }

    @Test
    fun `직전 N봉 확보 불가(N+1 미만)면 InsufficientCandlesException`() {
        val candles =
            listOf(
                candle(0, 100, 105, 98, 104),
                candle(1, 104, 108, 103, 107),
            )
        // period=2 → N+1=3 필요한데 2개뿐.
        shouldThrow<IndicatorException.InsufficientCandlesException> {
            IndicatorVolatility.donchianUpper(IndicatorSpec("donchian_upper", mapOf("period" to 2)), candles)
        }
    }

    private fun candle(
        minute: Int,
        open: Long,
        high: Long,
        low: Long,
        close: Long,
    ): Candle =
        Candle(
            id = null,
            code = "TEST",
            interval = CandleInterval.ONE_MIN,
            ts = Instant.parse("2026-05-30T00:00:00Z").plus(minute.minutes),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "TEST",
        )
}
