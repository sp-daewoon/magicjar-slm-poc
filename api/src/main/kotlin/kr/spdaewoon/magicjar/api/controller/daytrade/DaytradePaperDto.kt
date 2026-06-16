package kr.spdaewoon.magicjar.api.controller.daytrade

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal

/**
 * 발굴 → MOCK 페이퍼 dispatch REST DTO (ADR 0122 §3-R2 데이터 계약 LOCK · 사용자 메모리 feedback_swagger_pattern).
 *
 * Swagger 4단 LOCK — DTO 필드는 @Schema(description + example) 의무.
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 §4-1)
 */

@Schema(
    name = "PaperPromoteRequest",
    description =
        "발굴 setup → MOCK 페이퍼 전략 인스턴스 일괄 승격 요청 (사용자 명시 opt-in. 자동 진입 0 — ADR 0122 §1-2). " +
            "발굴 후보 TOP N 중 사용자가 선택한 종목(codes)을 빌트인 setup(presetKey)의 entry_dsl 로 묶어 " +
            "origin=PAPER_DISCOVERY·status=ACTIVE 전략 1개로 만든다(다중선택 일괄 승격 — spec §8-1).",
)
data class PaperPromoteRequest(
    @field:NotNull
    @field:Schema(description = "전략 소유자 사용자 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val userId: Long?,
    @field:NotBlank
    @field:Schema(
        description = "발굴 빌트인 setup key (strategy_research_candidate.candidate_key). 이 setup 의 entry_dsl·target/stop 을 전략으로 복제.",
        example = "DAYTRADE_MOMENTUM_BREAKOUT",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val presetKey: String?,
    @field:NotEmpty
    @field:Schema(
        description = "페이퍼로 감시할 종목 코드 목록 (발굴 결과 TOP N 중 사용자 선택). 빈 리스트 reject.",
        example = "[\"005930\", \"000660\"]",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val codes: List<String>?,
    @field:Schema(
        description = "장전 setup 기준 인터벌 (장중 분봉은 데이터 best-effort). default 1d.",
        example = "1d",
    )
    val interval: String = "1d",
    @field:Schema(
        description = "가상 자본 초기 금액(원) 사용자 override. null 이면 ENV default(1천만원 — spec §8-2).",
        example = "10000000",
    )
    val virtualCapitalKrw: BigDecimal? = null,
)

@Schema(name = "PaperStrategyDto", description = "생성/조회된 페이퍼(MOCK) 전략 — 실거래 아님(모의).")
data class PaperStrategyDto(
    @field:Schema(description = "페이퍼 전략 ID", example = "42")
    val strategyId: Long,
    @field:Schema(description = "전략 출처 — 항상 PAPER_DISCOVERY", example = "PAPER_DISCOVERY")
    val origin: String,
    @field:Schema(description = "발굴 setup key", example = "DAYTRADE_MOMENTUM_BREAKOUT")
    val presetKey: String,
    @field:Schema(description = "발굴 setup 제목", example = "모멘텀 돌파 단타")
    val presetTitle: String,
    @field:Schema(description = "페이퍼 감시 종목", example = "[\"005930\", \"000660\"]")
    val codes: List<String>,
    @field:Schema(description = "평가 인터벌", example = "[\"1d\"]")
    val intervals: List<String>,
    @field:Schema(description = "가상 자본 초기 금액(원)", example = "10000000")
    val virtualCapital: BigDecimal,
    @field:Schema(description = "전략 상태", example = "ACTIVE")
    val status: String,
    @field:Schema(description = "생성/갱신 시각(ISO-8601)", example = "2026-06-10T09:30:00Z")
    val updatedAt: String,
)

@Schema(name = "PaperPositionDto", description = "페이퍼(가상) 포지션 — 실 계좌 아님. 손익은 가상 ₩.")
data class PaperPositionDto(
    @field:Schema(description = "페이퍼 전략 ID", example = "42")
    val strategyId: Long,
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "종목 코드", example = "005930")
    val code: String,
    @field:Schema(description = "종목명", example = "삼성전자")
    val codeName: String?,
    @field:Schema(description = "보유 수량(가상)", example = "10")
    val quantity: Int,
    @field:Schema(description = "평단가(가상 ₩)", example = "71000.0000")
    val avgPrice: BigDecimal,
    @field:Schema(description = "진행 회차(분할 lot)", example = "1")
    val filledLots: Int,
    @field:Schema(description = "실현손익(가상 ₩)", example = "12000.0000")
    val realizedPnl: BigDecimal,
    @field:Schema(description = "청산 완료 여부(quantity=0)", example = "false")
    val isClosed: Boolean,
    @field:Schema(description = "갱신 시각(ISO-8601)", example = "2026-06-10T10:05:00Z")
    val updatedAt: String,
)
