package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * screening 매칭 종목 DTO (ADR 0071 §6-1 + spec §6-1).
 *
 * ScreenerMatch 도메인 모델 → REST 응답 변환.
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "screening 매칭 종목")
data class ScreenerMatchDto(
    @Schema(description = "랭킹 (1-base)", example = "1")
    val rank: Int,
    @Schema(description = "종목 코드 (KIS 6자리)", example = "005930")
    val instrumentCode: String,
    @Schema(description = "종목명", example = "삼성전자")
    val instrumentName: String,
    @Schema(description = "랭킹 점수 (0~1 normalize)", example = "0.854321")
    val score: BigDecimal,
    @Schema(
        description = "점수 구성 (signal·volume·atr·cap 4 기준)",
        example = """{"signal":0.400000,"volume":0.300000,"atr":0.180000,"cap":0.100000}""",
    )
    val scoreComponents: Map<String, BigDecimal>,
    @Schema(
        description = "BUY 시그널 사유 (DSL evalResult.reason 정합)",
        example = "RSI(14) < 30 + MACD bullish cross",
    )
    val reason: String,
)
