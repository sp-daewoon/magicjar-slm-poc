package kr.spdaewoon.magicjar.api.controller.risk

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.service.risk.RiskUsageService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 리스크 한도 현재 사용량 조회 컨트롤러 — GET /api/risk/usage (읽기 전용 · T-RISK).
 *
 * ## 목적 — 설정 화면 "각 한도에 지금 얼마나 가까운지"
 * 게이트가 실제 매수 판정에 쓰는 값과 **동일한 소스·산식**으로 사용량 snapshot 을 1개 API 로 내려준다.
 * position 섹션은 매수 사이징(PR #1011, consumer RiskGatewayEvaluator)이 호출하는 domain
 * RiskBudgetCalculator 와 같은 함수로 산출돼 "표시값 ≠ 실제판정" 위험을 차단한다.
 *
 * ## 읽기 전용 · 새 KIS 호출 0
 * 자본·사이징 로직 변경 0. 잔고는 Redis cache 읽기만(미존재 시 position 섹션 null) — KIS TPS 무영향.
 *
 * Swagger 4단 어노테이션 (feedback_swagger_pattern 정합).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Tag(name = "RiskUsage", description = "리스크 한도 현재 사용량 조회 — 게이트 실제 판정값과 동일 소스 (읽기 전용)")
@RestController
@RequestMapping("/api/risk")
class RiskUsageController(
    private val riskUsageService: RiskUsageService,
) {
    private val log = LoggerFactory.getLogger(RiskUsageController::class.java)

    @Operation(
        summary = "리스크 한도 현재 사용량 조회",
        description =
            "각 리스크 한도(포지션·일일손실·동시보유·거래빈도)에 지금 얼마나 가까운지 snapshot 을 반환합니다. " +
                "포지션(매수) 한도는 매수 사이징(PR #1011)이 실제 판정에 쓰는 RiskBudgetCalculator 와 동일 함수로 산출됩니다 " +
                "— 표시값과 실제 판정이 어긋나지 않습니다. " +
                "잔고는 Redis cache 읽기 전용이며 새 KIS 호출을 하지 않습니다. " +
                "cache 미존재(주문 경로가 아직 잔고를 채우지 않음) 시 position 섹션은 null 로 내려가며 프런트가 '준비중'으로 처리합니다. " +
                "strategyId 를 주면 그 전략의 거래 빈도 사용량(tradeFrequency)을 함께 내려줍니다(미지정 시 null).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "사용량 snapshot 조회 성공"),
        ApiResponse(
            responseCode = "500",
            description = "서버 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/usage")
    fun getUsage(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @Parameter(description = "거래 빈도 평가 대상 전략 ID (optional — 미지정 시 tradeFrequency=null)", example = "86")
        @RequestParam(required = false) strategyId: Long?,
    ): RiskUsageResponse {
        log.debug("risk usage 조회: userId={} strategyId={}", userId, strategyId)
        return riskUsageService.getUsage(userId, strategyId)
    }
}
