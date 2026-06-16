package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema

/**
 * universe 필터 DTO (ADR 0071 §2-2 B-1 + spec §6-1).
 *
 * null 필드는 미적용 (default KOSPI+KOSDAQ STOCK+ETF active).
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "universe 필터 (옵션 — null 필드는 기본값 적용)")
data class ScreenerFilterDto(
    @Schema(
        description = "시장 리스트 (기본 KOSPI+KOSDAQ)",
        example = """["KOSPI", "KOSDAQ"]""",
    )
    val markets: List<String>? = null,
    @Schema(
        description = "종목 타입 리스트 (기본 STOCK+ETF)",
        example = """["STOCK", "ETF"]""",
    )
    val types: List<String>? = null,
    @Schema(
        description = "시총 하한 (억 단위, 옵션 — null=미적용)",
        example = "1000",
    )
    val marketCapMin: Long? = null,
    @Schema(
        description = "5일 평균 거래대금 하한 (원, 옵션 — null=미적용)",
        example = "1000000000",
    )
    val avgVolumeMin: Long? = null,
)
