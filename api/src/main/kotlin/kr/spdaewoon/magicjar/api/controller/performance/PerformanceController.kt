package kr.spdaewoon.magicjar.api.controller.performance

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.dto.performance.EquityCurveDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceTradeBreakdownDto
import kr.spdaewoon.magicjar.api.service.performance.PerformanceService
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * Performance REST 컨트롤러 — ADR 0072 §6-4·§6-5 정합.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합):
 * @Tag(controller) · @Operation(method) · @Parameter(param) · @Schema(DTO)
 *
 * 3 endpoint:
 * - GET /api/performance/summary — KPI 카드용 (5 지표)
 * - GET /api/performance/equity-curve — 차트용 시계열
 * - GET /api/performance/trades — 실현손익 상세 근거
 *
 * Redis cache 1h (PerformanceService 내부).
 * userId: String — user_account.user_id VARCHAR(40) 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 * // KIS 호출 0 — 모든 데이터 영속 reuse (ADR 0072 §1-4 LOCK)
 */
@Tag(name = "Performance", description = "성과 통계 — 5 지표 (P&L·MDD·승률·평균 손익비) + Equity Curve 시계열. ADR 0072 Phase 2.")
@RestController
@RequestMapping("/api/performance")
class PerformanceController(
    private val performanceService: PerformanceService,
) {
    /**
     * GET /api/performance/summary
     *
     * KPI 카드용 성과 통계 요약.
     * period=DAY·WEEK·MONTH·YEAR·ALL + periodStart·periodEnd 사용자 지정.
     * Redis cache 1h (cacheHit 필드로 확인 가능).
     */
    @Operation(
        summary = "성과 통계 요약 조회 (KPI 카드용)",
        description = """
            기간 단위(period)별 5 지표 요약: 총 P&L · MDD · 승률 · 평균 손익비 · 거래 횟수.

            period 기본값 = MONTH. 기간 분기 (periodStart·periodEnd 미지정 시):
            - DAY: 오늘 1일
            - WEEK: 이번 주 월요일~오늘
            - MONTH: 이번 달 1일~오늘
            - YEAR: 올해 1월 1일~오늘
            - ALL: 최초 거래일~오늘

            Redis cache TTL 1h. cacheHit=true 시 캐시 응답.
            pnl_daily 데이터 부재 시 totalPnl=0 반환 (mock 합성 0 — ADR 0072 §1-2).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "성과 통계 요약 (cacheHit 필드 확인 권장)"),
            ApiResponse(responseCode = "400", description = "period 값 오류 (허용값: DAY·WEEK·MONTH·YEAR·ALL)"),
        ],
    )
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "사용자 ID (user_account.user_id — multi-tenant 격리)", example = "user_12345678")
        @RequestParam userId: String,
        @Parameter(description = "기간 단위 (DAY·WEEK·MONTH·YEAR·ALL)", example = "MONTH")
        @RequestParam(defaultValue = "MONTH") period: String,
        @Parameter(description = "기간 시작 일자 (옵션 — 미지정 시 period 기준 자동 계산)", example = "2026-05-01")
        @RequestParam(required = false) periodStart: LocalDate?,
        @Parameter(description = "기간 종료 일자 (옵션 — 미지정 시 오늘)", example = "2026-05-14")
        @RequestParam(required = false) periodEnd: LocalDate?,
        @Parameter(description = "거래 모드 필터 (REAL·MOCK). 미지정 시 전체", example = "MOCK")
        @RequestParam(required = false) mode: TradingMode?,
    ): PerformanceSummaryDto = performanceService.getSummary(userId, period, periodStart, periodEnd, mode)

    /**
     * GET /api/performance/equity-curve
     *
     * Equity Curve 시계열 조회.
     * 일별 누적 P&L 시계열. 차트 source.
     * 거래 없는 날은 점 생략 (LWC v4 time series 정합).
     */
    @Operation(
        summary = "Equity Curve 시계열 조회 (차트용)",
        description = """
            일별 누적 P&L 시계열. PerformanceDashboard equity curve 차트 source.

            from·to 기간 (기본: 최근 30일). 거래 없는 날은 포함 안 함.
            cumulativePnl 누적 monotonic 검증 권장 (시나리오 12).
            Redis cache TTL 1h.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Equity Curve 시계열 (points 배열 — 거래일만)"),
            ApiResponse(responseCode = "400", description = "from·to 파라미터 오류"),
        ],
    )
    @GetMapping("/equity-curve")
    fun equityCurve(
        @Parameter(description = "사용자 ID (user_account.user_id — multi-tenant 격리)", example = "user_12345678")
        @RequestParam userId: String,
        @Parameter(description = "조회 시작 일자 (옵션 — 미지정 시 오늘 기준 30일 전)", example = "2026-04-14")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "조회 종료 일자 (옵션 — 미지정 시 오늘)", example = "2026-05-14")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "거래 모드 필터 (REAL·MOCK). 미지정 시 전체", example = "MOCK")
        @RequestParam(required = false) mode: TradingMode?,
    ): EquityCurveDto = performanceService.getEquityCurve(userId, from, to, mode)

    /**
     * GET /api/performance/trades
     *
     * 총 손익의 근거를 매도 체결 단위로 조회.
     * 성과 배치와 동일하게 평균단가 방식으로 체결 이력을 replay한다.
     */
    @Operation(
        summary = "성과 실현손익 상세 조회",
        description = """
            총 손익의 근거가 되는 매도 체결별 상세 목록.
            period=DAY·WEEK·MONTH·YEAR·ALL + periodStart·periodEnd 사용자 지정.

            모든 데이터는 order_request·order_execution 영속 데이터 reuse.
            KIS endpoint 호출 0, AI 호출 0.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "실현손익 상세 목록"),
            ApiResponse(responseCode = "400", description = "period 값 오류 (허용값: DAY·WEEK·MONTH·YEAR·ALL)"),
        ],
    )
    @GetMapping("/trades")
    fun trades(
        @Parameter(description = "사용자 ID (user_account.user_id — multi-tenant 격리)", example = "user_12345678")
        @RequestParam userId: String,
        @Parameter(description = "기간 단위 (DAY·WEEK·MONTH·YEAR·ALL)", example = "MONTH")
        @RequestParam(defaultValue = "MONTH") period: String,
        @Parameter(description = "기간 시작 일자 (옵션 — 미지정 시 period 기준 자동 계산)", example = "2026-05-01")
        @RequestParam(required = false) periodStart: LocalDate?,
        @Parameter(description = "기간 종료 일자 (옵션 — 미지정 시 오늘)", example = "2026-05-22")
        @RequestParam(required = false) periodEnd: LocalDate?,
        @Parameter(description = "거래 모드 필터 (REAL·MOCK). 미지정 시 전체", example = "MOCK")
        @RequestParam(required = false) mode: TradingMode?,
    ): PerformanceTradeBreakdownDto = performanceService.getTradeBreakdown(userId, period, periodStart, periodEnd, mode)
}
