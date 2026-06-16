package kr.spdaewoon.magicjar.api.ai.explain

import com.fasterxml.jackson.annotation.JsonProperty
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import kr.spdaewoon.magicjar.domain.ai.AiCommentType

/**
 * AI 코멘트 요청 DTO (POST /api/ai/explain — ADR 0026 §2-4 amend).
 *
 * 진입점 5종 (ADR 0026 §2-2 amend): SYMBOL / CHART / STRATEGY / SIGNAL / MARKET
 * 매매 결정권 0 — 본 endpoint 는 advisory only (자동 매수·매도 0건).
 */
@Schema(description = "AI 코멘트 요청 — 사용자 명시 클릭 trigger only · 5 진입점 advisory only (매매 결정권 0)")
data class AiExplainRequest(
    @field:NotNull(message = "type 은 필수입니다 (SYMBOL / CHART / STRATEGY / SIGNAL / MARKET 중 1)")
    @field:Schema(
        description = "진입점 타입 — UI 의 어느 위치에서 호출됐는지 식별 (prompt_template 매핑 키)",
        example = "MARKET",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @JsonProperty("type")
    val type: AiCommentType,
    @field:NotNull(message = "userId 는 필수입니다 (1일 토큰 한도 집계 키)")
    @field:Positive(message = "userId 는 양수여야 합니다")
    @field:Schema(
        description = "호출자 사용자 ID — 1일 토큰 한도(`AiTokenLimitGuard`) 집계 키",
        example = "1",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @JsonProperty("userId")
    val userId: Long,
    @field:NotNull(message = "context 는 필수입니다 (prompt 변수 치환 입력)")
    @field:Schema(
        description =
            "prompt 변수 치환 입력 — type 별 V50 seed `variables_schema` 정합 " +
                "(예: market_v1 → kospiTrend·sectorTopList·maPeriods·rsPeriod)",
        example = "{\"code\":\"005930\",\"name\":\"삼성전자\"}",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @JsonProperty("context")
    val context: Map<String, Any>,
)

@Schema(description = "AI 코멘트 응답 — `requestId` 로 STOMP `/topic/ai.explain.{requestId}` 구독해 실시간 LLM 응답 수신")
data class AiExplainResponse(
    @field:Schema(
        description = "AI 요청 추적 ID — STOMP fanout 라우팅 키 (`/topic/ai.explain.{requestId}`)",
        example = "550e8400-e29b-41d4-a716-446655440000",
    )
    @JsonProperty("requestId")
    val requestId: String,
    @field:Schema(
        description = "요청 처리 상태 — `ACCEPTED` (Kafka publish 완료, LLM 호출 대기) / `REFUSED` (KillSwitch · 한도 초과 · 비활성)",
        example = "ACCEPTED",
    )
    @JsonProperty("status")
    val status: String,
    @field:Schema(
        description =
            "refuse 사유 코드 (status=REFUSED 일 때) — " +
                "KILLSWITCH_ENGAGED / AI_DISABLED / DAILY_TOKEN_LIMIT_EXCEEDED / PROMPT_NOT_FOUND",
        example = "AI_DISABLED",
        nullable = true,
    )
    @JsonProperty("refuseCode")
    val refuseCode: String? = null,
    @field:Schema(
        description = "사용자 친화 한국어 메시지 (status=REFUSED 일 때 사용자에게 노출) — frontend AiCard 표시",
        example = "AI 기능이 비활성 상태입니다. 설정에서 활성화한 후 다시 시도해 주세요.",
        nullable = true,
    )
    @JsonProperty("userMessage")
    val userMessage: String? = null,
)
