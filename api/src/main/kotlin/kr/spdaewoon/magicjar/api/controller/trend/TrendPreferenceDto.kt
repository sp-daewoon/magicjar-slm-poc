package kr.spdaewoon.magicjar.api.controller.trend

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import kr.spdaewoon.magicjar.domain.trend.TrendPreference
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import java.time.Instant

/**
 * TrendPreference REST DTO (Swagger 4단 — feedback_swagger_pattern 정합).
 *
 * - 응답: [TrendPreferenceResponse] · isUserCustom flag 포함
 * - 요청 (PUT): [TrendPreferenceUpdateRequest] · Bean Validation 강제
 */
@Schema(
    description = "사용자 추세 분석 설정 응답 (ADR 0101)",
    example = """{"maPeriods":[5,20,60],"rsPeriod":60,"crossPair":null,"isUserCustom":false,"updatedAt":"2026-05-24T10:00:00Z"}""",
)
data class TrendPreferenceResponse(
    @field:Schema(
        description = "MA 기간 list (1~5개·각 1~252일·sorted ASC)",
        example = "[5,20,60]",
    )
    val maPeriods: List<Int>,
    @field:Schema(
        description = "RS rating 비교 기간 (1~252일)",
        example = "60",
    )
    val rsPeriod: Int,
    @field:Schema(
        description = "crossPair 명시 — null 시 maPeriods 최소/최대 auto",
        nullable = true,
    )
    val crossPair: CrossPairDto?,
    @field:Schema(
        description = "default 와 다름 — frontend 'user pref' 배지 표시 신호",
        example = "false",
    )
    val isUserCustom: Boolean,
    @field:Schema(
        description = "최근 갱신 시각 (UTC)",
        example = "2026-05-24T10:00:00Z",
    )
    val updatedAt: Instant,
) {
    companion object {
        fun from(pref: TrendPreference): TrendPreferenceResponse =
            TrendPreferenceResponse(
                maPeriods = pref.maPeriods,
                rsPeriod = pref.rsPeriod,
                crossPair = pref.crossPair?.let { (s, l) -> CrossPairDto(short = s, long = l) },
                isUserCustom = !pref.isDefault(),
                updatedAt = pref.updatedAt,
            )
    }
}

@Schema(description = "MA cross pair (short < long 의무)")
data class CrossPairDto(
    @field:Schema(description = "short MA 기간", example = "5")
    @field:Min(value = 1, message = "short MA 기간은 1 이상이어야 합니다")
    @field:Max(value = 252, message = "short MA 기간은 252 이하여야 합니다")
    val short: Int,
    @field:Schema(description = "long MA 기간", example = "60")
    @field:Min(value = 1, message = "long MA 기간은 1 이상이어야 합니다")
    @field:Max(value = 252, message = "long MA 기간은 252 이하여야 합니다")
    val long: Int,
)

@Schema(
    description = "TrendPreference 변경 요청 (PUT /api/users/me/preferences/trend) — ADR 0101 §2-3-A",
    example = """{"maPeriods":[5,20,60],"rsPeriod":60,"crossPair":null}""",
)
data class TrendPreferenceUpdateRequest(
    @field:Schema(
        description = "MA 기간 list (1~5개·각 1~252일·sorted ASC + 중복 없음)",
        example = "[5,20,60]",
    )
    @field:Size(min = 1, max = 5, message = "MA periods 는 1~5개 사이여야 합니다")
    val maPeriods: List<
        @Min(value = 1, message = "각 MA 기간은 1 이상이어야 합니다")
        @Max(value = 252, message = "각 MA 기간은 252 이하여야 합니다")
        Int,
    >,
    @field:Schema(
        description = "RS rating 비교 기간 (1~252일). 생략 시 default 60.",
        example = "60",
    )
    @field:Min(value = 1, message = "RS period 는 1 이상이어야 합니다")
    @field:Max(value = 252, message = "RS period 는 252 이하여야 합니다")
    val rsPeriod: Int = TrendPreferenceDefaults.RS_PERIOD,
    @field:Schema(
        description = "crossPair — short < long · 둘 다 maPeriods 내 값 의무. null 시 maPeriods 최소/최대 auto.",
        nullable = true,
    )
    @field:Valid
    val crossPair: CrossPairDto? = null,
) {
    /** Pair<Int, Int>? 변환 — 도메인 layer 위임용. */
    fun crossPairAsTuple(): Pair<Int, Int>? = crossPair?.let { it.short to it.long }
}
