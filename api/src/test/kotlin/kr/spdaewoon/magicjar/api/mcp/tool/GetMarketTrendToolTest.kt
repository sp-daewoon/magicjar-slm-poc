package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.service.trend.SectorRankingService
import kr.spdaewoon.magicjar.api.service.trend.TrendPreferenceService
import kr.spdaewoon.magicjar.api.service.trend.TrendQueryService
import kr.spdaewoon.magicjar.domain.trend.CrossSignal
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRankEntry
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import kr.spdaewoon.magicjar.domain.trend.TrendLabel
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * GetMarketTrendTool — periods 생략 시 user pref echo + 명시 시 일회성 override (ADR 0101 정합).
 */
class GetMarketTrendToolTest {
    private val trendQuery: TrendQueryService = mock()
    private val sectorRanking: SectorRankingService = mock()
    private val prefService: TrendPreferenceService =
        mock {
            whenever(it.getOrDefault("user-1")).thenReturn(TrendPreferenceDefaults.forUser("user-1"))
        }
    private val tool = GetMarketTrendTool(trendQuery, sectorRanking, prefService)

    @Test
    fun `scope=index - periods 생략 시 user pref echo`() {
        whenever(trendQuery.snapshot("user-1")).thenReturn(listOf(sampleIndexTrend()))

        val response = tool.execute(mapOf("scope" to "index"), "user-1")

        assertThat(response["scope"]).isEqualTo("index")
        assertThat(response["periodsUsed"]).isEqualTo(TrendPreferenceDefaults.MA_PERIODS)
        assertThat(response["isUserCustom"]).isEqualTo(false)
        assertThat(response["indexTrends"]).isInstanceOf(List::class.java)
        verify(trendQuery).snapshot("user-1")
    }

    @Test
    fun `scope=sector - SectorRankingService reuse`() {
        whenever(sectorRanking.ranking(any(), anyOrNull(), anyOrNull(), any())).thenReturn(sampleSectorRanking())

        val response = tool.execute(mapOf("scope" to "sector"), "user-1")

        assertThat(response["scope"]).isEqualTo("sector")
        @Suppress("UNCHECKED_CAST")
        val ranking = response["sectorRanking"] as Map<String, Any?>
        assertThat(ranking["periodsUsed"]).isEqualTo(listOf(5, 20, 60))
        assertThat(response["indexTrends"]).isNull() // scope=sector → indexTrends 미포함
    }

    @Test
    fun `scope=both - index + sector 양쪽 응답`() {
        whenever(trendQuery.snapshot("user-1")).thenReturn(listOf(sampleIndexTrend()))
        whenever(sectorRanking.ranking(any(), anyOrNull(), anyOrNull(), any())).thenReturn(sampleSectorRanking())

        val response = tool.execute(mapOf("scope" to "both"), "user-1")

        assertThat(response["indexTrends"]).isNotNull
        assertThat(response["sectorRanking"]).isNotNull
    }

    @Test
    fun `periods 명시 시 - TrendQueryService trend(periodsOverride) path`() {
        whenever(trendQuery.trend(eq("user-1"), any(), eq(listOf(10, 30, 90))))
            .thenReturn(sampleIndexTrend(isUserCustom = true))

        val response =
            tool.execute(
                mapOf("scope" to "index", "periods" to listOf(10, 30, 90)),
                "user-1",
            )

        assertThat(response["periodsUsed"]).isEqualTo(listOf(10, 30, 90))
        assertThat(response["isUserCustom"]).isEqualTo(true)
        // 4 default index 모두 호출
        verify(trendQuery, org.mockito.kotlin.times(4)).trend(eq("user-1"), any(), eq(listOf(10, 30, 90)))
    }

    @Test
    fun `잘못된 periods - 한국어 McpToolException`() {
        assertThatThrownBy {
            tool.execute(mapOf("scope" to "index", "periods" to listOf(0, 5, 20)), "user-1")
        }.isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("1~252")

        assertThatThrownBy {
            tool.execute(mapOf("scope" to "index", "periods" to listOf(20, 5, 60)), "user-1")
        }.isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("오름차순")
    }

    @Test
    fun `scope 누락 - 한국어 message`() {
        assertThatThrownBy { tool.execute(emptyMap(), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("scope")
    }

    @Test
    fun `scope 잘못 - 한국어 message + 허용 list echo`() {
        assertThatThrownBy { tool.execute(mapOf("scope" to "invalid"), "user-1") }
            .isInstanceOf(McpToolException::class.java)
            .hasMessageContaining("허용")
    }

    private fun sampleIndexTrend(isUserCustom: Boolean = false): IndexTrend =
        IndexTrend(
            code = "0001",
            ma = mapOf(5 to BigDecimal("2670.12"), 20 to BigDecimal("2685.34"), 60 to BigDecimal("2710.55")),
            crossSignal = CrossSignal.NEUTRAL,
            trendLabel = TrendLabel.SIDEWAYS,
            periodsUsed = if (isUserCustom) listOf(10, 30, 90) else listOf(5, 20, 60),
            isUserCustom = isUserCustom,
            dataInsufficient = false,
            lastClose = BigDecimal("2700.00"),
            changeRate = BigDecimal("0.50"),
        )

    private fun sampleSectorRanking(): SectorRanking =
        SectorRanking(
            todayTop = listOf(sampleSectorEntry("S001", "반도체")),
            longTop = listOf(sampleSectorEntry("S002", "바이오")),
            periodsUsed = listOf(5, 20, 60),
            rsPeriod = 60,
            isUserCustom = false,
        )

    private fun sampleSectorEntry(
        code: String,
        name: String,
    ): SectorRankEntry =
        SectorRankEntry(
            sectorCode = code,
            sectorName = name,
            changeRate = BigDecimal("1.23"),
            tradeValue = BigDecimal("1000000000"),
            score = BigDecimal("0.85"),
            metric = "RS=1.12",
        )
}
