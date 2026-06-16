package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.IndicatorService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 지표 REST 컨트롤러 — KIS 80지표 + magicJar 확장 지표 시계열 계산 단일 endpoint (spec §6-3 LOCK).
 *
 * - GET /api/indicators?code=...&name=...&interval=...&params=...&from=...&to=...&adjusted=...
 *
 * IndicatorEngine은 api·consumer 양쪽에 bean 등록 (sources stateless — ADR 0021 §2-5, spec §3-3).
 * Redis 캐시 (indicator:cache:{code}:{interval}:{name}:{paramsHash}:{rangeHash}): 분봉 5분 / 일봉 1일 TTL.
 * 미지원 지표 → 400, 캔들 부족 → 400.
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "Indicators", description = "기술적 지표 82종 (KIS Strategy Builder + magicJar Native/Smart Money source) — T-IND W2")
@RestController
@RequestMapping("/api/indicators")
class IndicatorController(
    private val indicatorService: IndicatorService,
) {
    @Operation(
        summary = "지표 시계열 계산",
        description =
            "KIS 80지표 + magicJar 확장 지표 중 하나를 candle 시계열 위에서 계산하여 반환합니다. " +
                "결과는 Redis 캐시 적용 — 분봉/시간봉(1m·3m·5m·15m·30m·90m·1h·4h·12h) 5분 TTL, 일봉 이상(1d·1wk·1mo·1y) 1일 TTL. " +
                "미지원 지표 요청 시 400 에러. " +
                "캔들 데이터 부족(기간 < 필요 최소) 시 400 에러. " +
                "source 필드로 계산에 사용된 구현체 확인 가능 (native · smart-money · kis-strategy-builder).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "지표 계산 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 파라미터 — 미지원 지표 ID · 캔들 부족 · 종목코드 형식 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping
    fun getIndicator(
        @Parameter(description = "종목코드 6자리", example = "005930", required = true)
        @RequestParam code: String,
        @Parameter(description = "지표 ID (예: rsi · macd · bb · adx · ma · ema · ...)", example = "rsi", required = true)
        @RequestParam name: String,
        @Parameter(description = "캔들 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)", example = "1d", required = true)
        @RequestParam interval: String,
        @Parameter(
            description = "지표 파라미터 (key=value 쉼표 구분, 예: period=14 / fast=12,slow=26,signal=9)",
            example = "period=14",
        )
        @RequestParam(required = false) params: String?,
        @Parameter(description = "조회 시작 시각 (ISO 8601 UTC, 예: 2024-01-01T00:00:00Z)")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료 시각 (ISO 8601 UTC, 예: 2024-12-31T23:59:59Z)")
        @RequestParam(required = false) to: String?,
        @Parameter(description = "수정 주가 사용 여부 (T-CA1 정합 — default: false = raw 가격)", example = "false")
        @RequestParam(required = false, defaultValue = "false") adjusted: Boolean,
    ): IndicatorResultDto =
        indicatorService.calculate(
            code = code,
            name = name,
            interval = interval,
            paramsRaw = params,
            from = from,
            to = to,
            adjusted = adjusted,
        )
}
