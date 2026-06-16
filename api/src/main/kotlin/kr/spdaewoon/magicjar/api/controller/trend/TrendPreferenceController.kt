package kr.spdaewoon.magicjar.api.controller.trend

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.messaging.StompMarketNotifier
import kr.spdaewoon.magicjar.api.service.trend.TrendPreferenceService
import kr.spdaewoon.magicjar.api.service.trend.TrendUserResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 추세 설정 REST 표면 (ADR 0101 §2-3 — preference 2 endpoint).
 *
 * ## 인증
 * `OnboardingGuardFilter` 가 `/api/` 전반 셋업 게이트 처리. 본 controller 는 single-tenant 1차 패턴 —
 * `TrendUserResolver` 가 첫 user_account row userId 반환.
 *
 * ## Swagger 4단 (feedback_swagger_pattern 정합)
 * - `@Tag` controller
 * - `@Operation` 각 method
 * - `@Schema` 각 DTO
 * - description 한국어 + example 값
 */
@Tag(
    name = "사용자 설정 API",
    description = "사용자 mental model 단일 진입점 — 추세 분석 설정 외 모든 설정 확장 슬롯 (ADR 0101)",
)
@RestController
@RequestMapping("/api/users/me/preferences")
class TrendPreferenceController(
    private val trendPreferenceService: TrendPreferenceService,
    private val trendUserResolver: TrendUserResolver,
    private val stompMarketNotifier: StompMarketNotifier,
) {
    @Operation(
        summary = "추세 분석 설정 조회",
        description =
            "현재 사용자의 MA periods · RS period · cross pair 조회. " +
                "한 번도 PUT 한 적 없으면 default [5,20,60] · rsPeriod 60 echo + isUserCustom=false.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공 (default fallback 포함)"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료 (ONBOARDING_REQUIRED)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/trend")
    fun getTrendPreference(): TrendPreferenceResponse {
        val userId = trendUserResolver.currentUserId()
        val pref = trendPreferenceService.getOrDefault(userId)
        return TrendPreferenceResponse.from(pref)
    }

    @Operation(
        summary = "추세 분석 설정 변경",
        description =
            "MA periods 1~5개 · 각 1~252일 · sorted ASC + 중복 없음. RS period 1~252. " +
                "crossPair 명시 시 short < long · 둘 다 maPeriods 내 의무. 위반 시 400 한국어. " +
                "성공 시 STOMP `/topic/market.user.{userId}.pref` fanout (다중 탭 sync).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 위반 (Bean Validation 한국어 message)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/trend")
    fun updateTrendPreference(
        @Valid @RequestBody request: TrendPreferenceUpdateRequest,
    ): TrendPreferenceResponse {
        val userId = trendUserResolver.currentUserId()
        val updated =
            trendPreferenceService.update(
                userId = userId,
                maPeriods = request.maPeriods,
                rsPeriod = request.rsPeriod,
                crossPair = request.crossPairAsTuple(),
            )
        // STOMP fanout — 다중 탭 sync
        stompMarketNotifier.publishUserPreference(updated)
        return TrendPreferenceResponse.from(updated)
    }
}
