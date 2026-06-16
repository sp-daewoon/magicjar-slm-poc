package kr.spdaewoon.magicjar.api.strategybuilder

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 전략빌더 AI 전략 생성 요청.
 *
 * Live-Context Meta-Prompt 패턴(AI 프리셋 추천 PR #777 확립)의 2번째 이식.
 * 사용자 prompt(전략 의도)를 magicJar DSL 문법·지표 카탈로그·기존 전략·RiskPlan 스키마 컨텍스트와 묶어
 * Claude에게 빌더 draft 후보를 받아온다. 저장하지 않으며, AI 매매 결정권 0(ADR 0112 정합).
 */
@Schema(description = "전략빌더 AI 전략 생성 요청")
data class StrategyBuilderAiGenerateRequestDto(
    @field:Schema(description = "사용자 ID", example = "74683223")
    @field:NotBlank
    val userId: String,
    @field:Schema(
        description = "사용자 전략 의도 자연어(필수). 어떤 시장·타이밍·근거로 진입/청산할지 서술.",
        example = "20일선 위에서 눌림목 후 거래량이 붙으며 반등하는 스윙 전략, 단타보다는 며칠 보유",
    )
    @field:NotBlank
    @field:Size(max = 2000)
    val prompt: String,
    @field:Schema(description = "타임프레임 힌트(옵션). 빌더 저장 tf와 정합용.", example = "1d")
    @field:Pattern(regexp = "1m|3m|5m|15m|30m|90m|1h|4h|12h|1d|1wk|1mo|1y")
    val interval: String? = null,
    @field:Schema(description = "받을 전략 후보 개수(1~3, 기본 1)", example = "1")
    @field:Min(1)
    @field:Max(3)
    val count: Int = 1,
)

@Schema(description = "전략빌더 AI 전략 생성 응답. items는 빌더 draft 로드용 제안이며 저장되지 않는다.")
data class StrategyBuilderAiGenerateResponseDto(
    @field:Schema(description = "생성을 수행한 provider", example = "claude-cli")
    val provider: String,
    @field:Schema(description = "생성 시각 ISO-8601", example = "2026-06-08T12:00:00Z")
    val generatedAt: String,
    @field:Schema(description = "주의 사항·안내·잔익절 경고 문구")
    val warnings: List<String>,
    @field:Schema(description = "AI가 설계한 전략 후보 목록")
    val items: List<StrategyBuilderAiGenerateItemDto>,
)

@Schema(description = "AI가 설계한 전략 후보 1건. dslValid=true면 그대로 빌더 draft로 로드할 수 있다.")
data class StrategyBuilderAiGenerateItemDto(
    @field:Schema(description = "전략 노출명", example = "20일선 눌림목 거래량 반등 스윙")
    val name: String,
    @field:Schema(description = "전략 설명(무엇을·왜)", example = "20일선 위 눌림목에서 거래량이 동반 회복될 때 진입하는 스윙 전략입니다.")
    val description: String,
    @field:Schema(
        description = "진입 조건 DSL(파서 화이트리스트 문법만)",
        example = "close > ma(20) and close crosses_above ma(5) and volume > volume_ma(20) * 1.5",
    )
    val entryDsl: String,
    @field:Schema(description = "청산 조건 DSL(없으면 null — 익절/손절/보유로 청산)", example = "close crosses_below ma(5)")
    val exitDsl: String?,
    @field:Schema(description = "리스크 플랜(익절·손절·최소 보유 bar)")
    val riskPlan: StrategyBuilderAiRiskPlanDto,
    @field:Schema(description = "왜 이 지표 조합이 의도에 맞는지 1~2문장", example = "20일선·거래량 이동평균으로 추세 필터와 수급 확인을 함께 검증할 수 있기 때문입니다.")
    val rationale: String,
    @field:Schema(description = "entryDsl/exitDsl이 실제 DSL 파서를 통과했는지 여부", example = "true")
    val dslValid: Boolean,
    @field:Schema(description = "DSL 검증 실패 사유(통과 시 null)", example = "Unknown DSL identifier: prev_drop")
    val dslError: String?,
)

@Schema(description = "AI 전략 후보 리스크 플랜. 자동매매 익절·손절·최소 보유는 사용자가 빌더에서 확정한다.")
data class StrategyBuilderAiRiskPlanDto(
    @field:Schema(description = "목표 익절 %(없으면 null)", example = "1.5")
    val takeProfitPct: Double?,
    @field:Schema(description = "hard stop 손절 %(없으면 null)", example = "0.8")
    val fixedStopLossPct: Double?,
    @field:Schema(description = "최소 보유 bar 수(없으면 null)", example = "3")
    val minHoldBars: Int?,
)
