package kr.spdaewoon.magicjar.api.controller.trend

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.service.trend.SectorRankingService
import kr.spdaewoon.magicjar.api.service.trend.TrendUserResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 섹터 snapshot / ranking REST 표면 (ADR 0100 §2-5 — sectors 2 endpoint).
 *
 * - GET `/api/market/sectors/snapshot` — KRX 21 섹터 1분 snapshot
 * - GET `/api/market/sectors/ranking?periods=5,20,60&rsPeriod=60` — todayTop 5 + longTop 5
 *
 * R1 market-data 미머지 시점에는 `NoopMarketIndexSnapshotReader` 가 빈 list 반환 →
 * frontend 는 "데이터 없음" 한국어 표시 (mock 합성 0 — feedback_no_mock_data 정합).
 */
@Tag(
    name = "시장 섹터 API",
    description = "KRX 21 섹터 snapshot + ranking (사용자 MA periods · RS period 기본 적용 · ADR 0100)",
)
@RestController
@RequestMapping("/api/market/sectors")
class MarketSectorController(
    private val sectorRankingService: SectorRankingService,
    private val trendUserResolver: TrendUserResolver,
) {
    @Operation(
        summary = "섹터 snapshot list",
        description =
            "KRX 21 섹터 + KOSPI/KOSDAQ 추가 sub-index 의 1분 단위 최신 snapshot. " +
                "각 row 는 sectorCode · sectorName · price · changeRate · tradeValue. " +
                "데이터 0건 시 빈 list 반환 (mock 합성 0 — frontend '데이터 없음' 한국어 표시).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/snapshot")
    fun snapshot(): SectorsSnapshotResponse {
        // 셋업 게이트 통과 의무
        trendUserResolver.currentUserId()
        val snapshots = sectorRankingService.snapshot()
        return SectorsSnapshotResponse(sectors = snapshots.map { SectorSnapshotItem.from(it) })
    }

    @Operation(
        summary = "섹터 ranking (todayTop · longTop)",
        description =
            "todayTop 5 = 오늘 강세 sector (등락 0.7 + 거래대금 0.3 가중). " +
                "longTop 5 = 장기 강세 sector (Mansfield RS rsPeriod 기준 vs KOSPI + 우상향 fit 가산점). " +
                "`periods` · `rsPeriod` 쿼리 생략 시 사용자 TrendPreference 자동 적용 + 응답 echo.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "400",
            description = "쿼리 파라미터 형식 위반",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/ranking")
    fun ranking(
        @Parameter(
            description = "MA 기간 list — comma 구분 (예: '5,20,60'). 생략 시 user pref.",
            example = "5,20,60",
        )
        @RequestParam(required = false) periods: String?,
        @Parameter(
            description = "Mansfield RS 비교 기간 (영업일). 생략 시 user pref.",
            example = "60",
        )
        @RequestParam(required = false) rsPeriod: Int?,
    ): SectorRankingResponse {
        val userId = trendUserResolver.currentUserId()
        val parsedPeriods = parsePeriods(periods)
        if (rsPeriod != null) {
            require(rsPeriod in 1..252) { "rsPeriod 는 1~252 사이여야 합니다 (입력=$rsPeriod)" }
        }
        val ranking =
            sectorRankingService.ranking(
                userId = userId,
                periodsOverride = parsedPeriods,
                rsPeriodOverride = rsPeriod,
            )
        return SectorRankingResponse.from(ranking)
    }

    private fun parsePeriods(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val ints =
            parts.map {
                it.toIntOrNull() ?: throw IllegalArgumentException(
                    "periods 형식 오류 — comma 구분 정수 list 필수 (입력=$raw)",
                )
            }
        require(ints.size in 1..5) { "periods 는 1~5개 사이여야 합니다 (입력=$raw)" }
        require(ints.all { it in 1..252 }) { "각 period 는 1~252 사이여야 합니다 (입력=$raw)" }
        require(ints == ints.sorted().distinct()) { "periods 는 sorted ASC + 중복 없음 (입력=$raw)" }
        return ints
    }
}
