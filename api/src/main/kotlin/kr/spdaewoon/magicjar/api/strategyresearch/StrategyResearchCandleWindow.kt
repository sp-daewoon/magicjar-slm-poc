package kr.spdaewoon.magicjar.api.strategyresearch

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal data class StrategyResearchCandleWindow(
    val interval: String,
    val from: Instant,
    val to: Instant,
)

internal object StrategyResearchCandleWindows {
    private val kstZone: ZoneId = ZoneId.of("Asia/Seoul")
    private val wholeDayIntervals = setOf("1d", "1wk", "1mo", "1y")

    fun of(
        interval: String,
        from: Instant,
        to: Instant,
    ): StrategyResearchCandleWindow {
        if (interval !in wholeDayIntervals) {
            return StrategyResearchCandleWindow(interval = interval, from = from, to = to)
        }

        val normalizedFrom =
            from
                .atZone(kstZone)
                .toLocalDate()
                .atStartOfDay(kstZone)
                .toInstant()
        val normalizedTo =
            to
                .atZone(kstZone)
                .toLocalDate()
                .atTime(LocalTime.MAX)
                .atZone(kstZone)
                .toInstant()
        return StrategyResearchCandleWindow(interval = interval, from = normalizedFrom, to = normalizedTo)
    }
}
