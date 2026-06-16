package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 잔익절 방어 B안 — [MinHoldGuard] 순수 함수 결정론 테스트.
 *
 * 손절·익절 비차단은 [RealtimeSignalGenerator] 호출 위치(DSL SELL 경로에만 적용)로 보장하므로,
 * 여기서는 게이트 자체의 경과 판정·비활성·경계·진입기록 부재 분기를 검증한다.
 */
class MinHoldGuardTest {
    private val opened = Instant.parse("2026-06-08T00:00:00Z")

    @Test
    fun `minHoldBars null이면 비활성 — 항상 SELL 허용(skip false)`() {
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = null,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened,
                boundaryTs = opened, // 경과 0이어도 비활성이면 false
            )
        assertThat(result).isFalse()
    }

    @Test
    fun `minHoldBars 0이면 비활성 — skip false`() {
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 0,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened,
                boundaryTs = opened,
            )
        assertThat(result).isFalse()
    }

    @Test
    fun `openedAt null(진입 기록 부재)이면 게이트 미적용 — skip false`() {
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 3,
                interval = CandleInterval.ONE_MIN,
                openedAt = null,
                boundaryTs = opened.plusSeconds(10_000),
            )
        assertThat(result).isFalse()
    }

    @Test
    fun `N캔들 경과 전이면 DSL SELL skip(true) — 1m x 3 = 180초 미만`() {
        // 진입 후 2분(120초) 경과 — 3캔들(180초) 미만 → skip
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 3,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened,
                boundaryTs = opened.plusSeconds(120),
            )
        assertThat(result).isTrue()
    }

    @Test
    fun `N캔들 정확히 경과(경계)면 SELL 허용 — 경과 == 필요시간이면 충족`() {
        // 1m x 3 = 180초. 정확히 180초 경과 → elapsed(180) < required(180) == false → 허용
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 3,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened,
                boundaryTs = opened.plusSeconds(180),
            )
        assertThat(result).isFalse()
    }

    @Test
    fun `N캔들 경과 후면 SELL 허용(skip false)`() {
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 3,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened,
                boundaryTs = opened.plusSeconds(181),
            )
        assertThat(result).isFalse()
    }

    @Test
    fun `interval 길이 반영 — 5m x 2 = 600초 미만이면 skip`() {
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 2,
                interval = CandleInterval.FIVE_MIN,
                openedAt = opened,
                boundaryTs = opened.plusSeconds(599),
            )
        assertThat(result).isTrue()

        val afterBoundary =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 2,
                interval = CandleInterval.FIVE_MIN,
                openedAt = opened,
                boundaryTs = opened.plusSeconds(600),
            )
        assertThat(afterBoundary).isFalse()
    }

    @Test
    fun `openedAt이 미래(데이터 이상)면 보수적으로 skip(true)`() {
        // boundaryTs < openedAt → elapsed 음수 → required 미만 → skip
        val result =
            MinHoldGuard.shouldSkipDslSell(
                minHoldBars = 1,
                interval = CandleInterval.ONE_MIN,
                openedAt = opened.plusSeconds(60),
                boundaryTs = opened,
            )
        assertThat(result).isTrue()
    }

    @Test
    fun `intervalSeconds 매핑 정합 — 1m=60 5m=300 1d=86400`() {
        assertThat(MinHoldGuard.intervalSeconds(CandleInterval.ONE_MIN)).isEqualTo(60L)
        assertThat(MinHoldGuard.intervalSeconds(CandleInterval.FIVE_MIN)).isEqualTo(300L)
        assertThat(MinHoldGuard.intervalSeconds(CandleInterval.ONE_DAY)).isEqualTo(86_400L)
    }

    private fun Instant.plusSeconds(s: Long): Instant = Instant.fromEpochSeconds(epochSeconds + s)
}
