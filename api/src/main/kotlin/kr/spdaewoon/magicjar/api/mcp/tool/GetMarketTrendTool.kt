package kr.spdaewoon.magicjar.api.mcp.tool

import kr.spdaewoon.magicjar.api.mcp.McpTool
import kr.spdaewoon.magicjar.api.mcp.McpToolException
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalInt
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.optionalIntList
import kr.spdaewoon.magicjar.api.mcp.tool.McpParamSupport.requireEnum
import kr.spdaewoon.magicjar.api.service.trend.SectorRankingService
import kr.spdaewoon.magicjar.api.service.trend.TrendPreferenceService
import kr.spdaewoon.magicjar.api.service.trend.TrendQueryService
import kr.spdaewoon.magicjar.domain.trend.IndexTrend
import kr.spdaewoon.magicjar.domain.trend.SectorRankEntry
import kr.spdaewoon.magicjar.domain.trend.SectorRanking
import org.springframework.stereotype.Component

/**
 * MCP tool `get_market_trend` — 지수·섹터 추세 snapshot (2026-05-24 amend · ADR 0027 §2-2·§2-3 amend).
 *
 * 기존 [TrendQueryService] · [SectorRankingService] · [TrendPreferenceService] reuse — modify 0 (R1 backend-core T-MARKET Phase 2 산출 reuse).
 *
 * ## periods 처리 정책 (ADR 0101 정합)
 * - `periods` 생략 시 → user pref echo (isUserCustom 필드로 frontend 가 default vs user 구분 표시)
 * - `periods` 명시 시 → 일회성 override (URL share / 테스트 path · pref 영속 0)
 *
 * ## input
 * - `scope`: index · sector · both (필수)
 * - `periods`: 정수 list 1~5개 (옵션 · 생략 시 user pref · TrendPreference invariant 검증)
 * - `rsPeriod`: 1~252 (옵션 · default user pref)
 *
 * ## output
 * `{ scope, indexTrends?: [...], sectorRanking?: { todayTop, longTop, ... } }`
 */
@Component
class GetMarketTrendTool(
    private val trendQueryService: TrendQueryService,
    private val sectorRankingService: SectorRankingService,
    private val trendPreferenceService: TrendPreferenceService,
) : McpTool {
    override val name: String = "get_market_trend"
    override val description: String =
        "지수·섹터 추세 snapshot (사용자 MA periods 기본 적용 — ADR 0101 TrendPreference). " +
            "periods 생략 시 user preference echo, 명시 시 일회성 override."

    override val inputSchema: Map<String, Any?> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "scope" to
                        mapOf(
                            "type" to "string",
                            "enum" to SCOPE_ALLOWED.sorted(),
                            "description" to "조회 범위: index=4 지수 (KOSPI·KOSDAQ·NASDAQ·S&P) · sector=KRX 21 업종 + ranking · both=양쪽",
                        ),
                    "periods" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 252),
                            "minItems" to 1,
                            "maxItems" to 5,
                            "description" to "MA 기간 list (1~5개·각 1~252일·sorted ASC). 생략 시 user preference (ADR 0101) 적용.",
                        ),
                    "rsPeriod" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to 252,
                            "default" to 60,
                            "description" to "RS rating 비교 기간 (Mansfield RS — 섹터 vs KOSPI). default 60.",
                        ),
                ),
            "required" to listOf("scope"),
        )

    override fun execute(
        params: Map<String, Any?>,
        userId: String,
    ): Map<String, Any?> {
        val scope = params.requireEnum("scope", SCOPE_ALLOWED)
        val periodsOverride = params.optionalIntList("periods", minSize = 1, maxSize = 5)
        val rsPeriodOverride = params.optionalInt("rsPeriod", min = 1, max = 252)

        // periods 명시 시 → TrendPreference invariant 사전 검증 (require 4건 — sorted/distinct/range)
        periodsOverride?.also { validatePeriods(it) }

        val pref = trendPreferenceService.getOrDefault(userId)
        val effectivePeriods = periodsOverride ?: pref.maPeriods
        val effectiveRsPeriod = rsPeriodOverride ?: pref.rsPeriod

        val response =
            mutableMapOf<String, Any?>(
                "scope" to scope,
                "periodsUsed" to effectivePeriods,
                "rsPeriod" to effectiveRsPeriod,
                "isUserCustom" to (periodsOverride != null || rsPeriodOverride != null || !pref.isDefault()),
            )

        if (scope == "index" || scope == "both") {
            response["indexTrends"] = loadIndexTrends(userId, periodsOverride)
        }
        if (scope == "sector" || scope == "both") {
            response["sectorRanking"] = loadSectorRanking(userId, periodsOverride, rsPeriodOverride).toMap()
        }

        return response
    }

    private fun loadIndexTrends(
        userId: String,
        periodsOverride: List<Int>?,
    ): List<Map<String, Any?>> {
        // periodsOverride 가 있으면 4 지수 모두 동일 override 적용 (URL share 시나리오 — frontend 4 카드 같은 periods)
        // 없으면 snapshot() 가 user pref 자동 사용
        return if (periodsOverride == null) {
            trendQueryService.snapshot(userId).map { it.toMap() }
        } else {
            TrendQueryService.INDEX_CODES_DEFAULT.map { code ->
                trendQueryService.trend(userId, code, periodsOverride).toMap()
            }
        }
    }

    private fun loadSectorRanking(
        userId: String,
        periodsOverride: List<Int>?,
        rsPeriodOverride: Int?,
    ): SectorRanking =
        sectorRankingService.ranking(
            userId = userId,
            periodsOverride = periodsOverride,
            rsPeriodOverride = rsPeriodOverride,
        )

    /**
     * periods 사전 검증 — TrendPreference invariant (ADR 0101 §2-1 require 5건 중 4건 — userId·rsPeriod 외).
     *
     * service 계층 init {} 가 다시 검증하지만 — 외부 client 친화 한국어 message 를 위해 사전 fail-fast.
     */
    private fun validatePeriods(periods: List<Int>) {
        periods.firstOrNull { it !in 1..252 }?.let {
            throw McpToolException("periods 각 값은 1~252 (위반 입력=$it)")
        }
        if (periods != periods.sorted().distinct()) {
            throw McpToolException("periods 는 중복 없이 오름차순 (입력=$periods)")
        }
    }

    private fun IndexTrend.toMap(): Map<String, Any?> =
        mapOf(
            "code" to code,
            "ma" to ma.mapKeys { it.key.toString() }.mapValues { it.value.toPlainString() },
            "crossSignal" to crossSignal.name,
            "trendLabel" to trendLabel.name,
            "periodsUsed" to periodsUsed,
            "isUserCustom" to isUserCustom,
            "dataInsufficient" to dataInsufficient,
            "lastClose" to lastClose?.toPlainString(),
            "changeRate" to changeRate?.toPlainString(),
        )

    private fun SectorRanking.toMap(): Map<String, Any?> =
        mapOf(
            "todayTop" to todayTop.map { it.toMap() },
            "longTop" to longTop.map { it.toMap() },
            "periodsUsed" to periodsUsed,
            "rsPeriod" to rsPeriod,
            "isUserCustom" to isUserCustom,
        )

    private fun SectorRankEntry.toMap(): Map<String, Any?> =
        mapOf(
            "sectorCode" to sectorCode,
            "sectorName" to sectorName,
            "changeRate" to changeRate.toPlainString(),
            "tradeValue" to tradeValue?.toPlainString(),
            "score" to score.toPlainString(),
            "metric" to metric,
        )

    private companion object {
        val SCOPE_ALLOWED: Set<String> = setOf("index", "sector", "both")
    }
}
