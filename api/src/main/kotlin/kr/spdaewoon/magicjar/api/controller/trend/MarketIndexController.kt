package kr.spdaewoon.magicjar.api.controller.trend

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.service.trend.TrendQueryService
import kr.spdaewoon.magicjar.api.service.trend.TrendUserResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 지수 카드 / 추세 상세 REST 표면 (ADR 0100 §2-5 — index 2 endpoint).
 *
 * - GET `/api/market/index/snapshot` — 4 지수 카드 (KOSPI · KOSDAQ · NASDAQ · S&P)
 * - GET `/api/market/index/{code}/trend?periods=5,20,60` — 단일 지수 상세 (periods 생략 시 user pref)
 * - GET `/api/market/index/{code}/candles?interval=1d&limit=260` — 지수 snapshot 기반 캔들
 *
 * Swagger 4단 의무 (feedback_swagger_pattern 정합).
 */
@Tag(
    name = "시장 지수 API",
    description = "지수 4 카드 + 추세 상세 (사용자 MA periods 기본 적용 · ADR 0100)",
)
@RestController
@RequestMapping("/api/market/index")
class MarketIndexController(
    private val trendQueryService: TrendQueryService,
    private val trendUserResolver: TrendUserResolver,
) {
    @Operation(
        summary = "4 지수 카드 snapshot",
        description =
            "KOSPI(0001) · KOSDAQ(1001) · NASDAQ(.IXIC) · S&P 500(.SPX) 4 지수 추세 카드. " +
                "사용자 TrendPreference 적용 MA + cross + trend 라벨 포함. " +
                "데이터 0건 시 `dataInsufficient=true` echo (frontend '데이터 없음' 한국어 표시 의무).",
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
    fun snapshot(): IndexSnapshotResponse {
        val userId = trendUserResolver.currentUserId()
        val list = trendQueryService.snapshot(userId)
        return IndexSnapshotResponse(indexes = list.map { IndexTrendResponse.from(it) })
    }

    @Operation(
        summary = "단일 지수 추세 상세",
        description =
            "지수 단일 추세 (MA · cross · trend label · last close · change rate). " +
                "`periods` 쿼리 파라미터 명시 시 일회성 override (URL share · 테스트용). " +
                "생략 시 사용자 TrendPreference 자동 적용 + 응답 `periodsUsed` echo.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "400",
            description = "periods 형식 위반 (예: 비숫자·6개 초과·중복)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/trend")
    fun trend(
        @Parameter(
            description = "KIS index code (0001=KOSPI · 1001=KOSDAQ · .IXIC=NASDAQ · .SPX=S&P · 업종코드)",
            example = "0001",
        )
        @PathVariable code: String,
        @Parameter(
            description = "MA 기간 list — comma 구분 (예: '5,20,60'). 생략 시 user pref. 1~5개 · 각 1~252.",
            example = "5,20,60",
        )
        @RequestParam(required = false) periods: String?,
    ): IndexTrendResponse {
        val userId = trendUserResolver.currentUserId()
        val parsed = parsePeriods(periods)
        val trend = trendQueryService.trend(userId = userId, code = code, periodsOverride = parsed)
        return IndexTrendResponse.from(trend)
    }

    @Operation(
        summary = "단일 지수 캔들",
        description =
            "market_index_snapshot 실제 수집값을 오래된 → 최신 순서 캔들로 반환합니다. " +
                "기본값 1d는 KST 날짜별 최신 snapshot series를 사용하고, 1m은 인접 snapshot 가격으로 OHLC를 파생합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/candles")
    fun candles(
        @Parameter(
            description = "KIS index code (0001=KOSPI · 1001=KOSDAQ · .IXIC=NASDAQ · .SPX=S&P)",
            example = "0001",
        )
        @PathVariable code: String,
        @Parameter(description = "캔들 간격. 현재 1d 또는 1m 지원", example = "1d")
        @RequestParam(defaultValue = "1d") interval: String,
        @Parameter(description = "조회 개수. 1~1000 사이로 clamp", example = "240")
        @RequestParam(defaultValue = "240") limit: Int,
    ): IndexCandlesResponse {
        val effectiveInterval = if (interval == "1d") "1d" else "1m"
        val candles =
            trendQueryService
                .candles(code = code, interval = effectiveInterval, limit = limit)
                .map { IndexCandleItem.from(it) }
        return IndexCandlesResponse(
            code = code,
            interval = effectiveInterval,
            candles = candles,
        )
    }

    /**
     * "5,20,60" → List<Int> — 위반 시 IllegalArgumentException → Spring 400.
     * 본 단순 helper 가 자체 추론 0 — 위반 시 한국어 메시지 반환.
     */
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
