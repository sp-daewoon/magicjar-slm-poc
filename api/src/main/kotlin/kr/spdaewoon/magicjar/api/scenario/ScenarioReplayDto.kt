package kr.spdaewoon.magicjar.api.scenario

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal

@Schema(description = "시나리오 리플레이 실행 요청")
data class ScenarioReplayRunRequestDto(
    @field:Schema(description = "사용자 ID", example = "74683223")
    @field:NotBlank
    val userId: String,
    @field:Schema(description = "전략 ID", example = "43")
    @field:Min(1)
    val strategyId: Long,
    @field:Schema(description = "종목코드 6자리", example = "005930")
    @field:Pattern(regexp = "\\d{6}")
    val code: String,
    @field:Schema(description = "캔들 interval", example = "1m")
    @field:NotBlank
    val interval: String,
    @field:Schema(description = "리플레이 시작 시각 ISO-8601", example = "2026-06-02T04:00:00Z")
    @field:NotBlank
    val from: String,
    @field:Schema(description = "리플레이 종료 시각 ISO-8601", example = "2026-06-02T05:10:00Z")
    @field:NotBlank
    val to: String,
    @field:Schema(description = "가상 시작 현금", example = "1000000")
    @field:DecimalMin("0.0")
    val initialCash: BigDecimal,
    @field:Schema(description = "가상 시작 포지션")
    val initialPositions: List<ScenarioPositionSeedDto> = emptyList(),
    @field:Schema(description = "장애 주입 조건")
    val faults: List<ScenarioFaultDto> = emptyList(),
)

@Schema(description = "시나리오 리플레이 시작 포지션")
data class ScenarioPositionSeedDto(
    @field:Schema(description = "종목코드 6자리", example = "000660")
    @field:Pattern(regexp = "\\d{6}")
    val code: String,
    @field:Schema(description = "보유 수량", example = "1")
    @field:Min(0)
    val quantity: Long,
    @field:Schema(description = "평균 단가", example = "150000")
    @field:DecimalMin("0.0")
    val averagePrice: BigDecimal,
)

@Schema(description = "시나리오 리플레이 장애 주입")
data class ScenarioFaultDto(
    @field:Schema(description = "장애 발생 가상 시각 ISO-8601", example = "2026-06-02T07:15:00Z")
    @field:NotBlank
    val at: String,
    @field:Schema(description = "장애 타입", example = "BALANCE_LOOKUP_RATE_LIMITED")
    @field:NotBlank
    val type: String,
    @field:Schema(description = "사용자 표시 메시지", example = "KIS 잔고 조회 rate limit으로 가상 주문 보류")
    val message: String? = null,
)

data class ScenarioRunSummaryDto(
    val id: Long,
    val userId: String,
    val strategyId: Long,
    val strategyName: String?,
    val code: String,
    val interval: String,
    val rangeFrom: String,
    val rangeTo: String,
    val initialCash: BigDecimal,
    val fillModel: String,
    val status: String,
    val eventCount: Int,
    val finalCash: BigDecimal?,
    val abortReason: String?,
    val createdAt: String,
)

data class ScenarioRunDetailDto(
    val run: ScenarioRunSummaryDto,
    val events: List<ScenarioEventDto>,
    val positions: List<ScenarioPositionDto>,
)

data class ScenarioEventDto(
    val id: Long?,
    val sequenceNo: Int,
    val virtualTime: String,
    val type: String,
    val severity: String,
    val title: String,
    val message: String,
    val strategyId: Long?,
    val strategyName: String?,
    val code: String?,
    val payload: Map<String, Any?>,
)

data class ScenarioPositionDto(
    val id: Long?,
    val code: String,
    val quantity: Long,
    val averagePrice: BigDecimal,
    val virtualTime: String,
)

data class ScenarioPageDto<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
