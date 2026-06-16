package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult

/**
 * 지표 REST 응답 DTO (spec §6-3 LOCK).
 *
 * BigDecimal·Instant 를 문자열로 직렬화 (JSON 정밀도 보존 + frontend 파싱 단순화).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Schema(description = "지표 계산 결과 응답")
data class IndicatorResultDto(
    @Schema(description = "계산에 사용된 지표 명세")
    val spec: SpecDto,
    @Schema(description = "지표 시계열 (ts ASC 정렬)")
    val series: List<PointDto>,
    @Schema(description = "계산 source 이름 (native · kis-strategy-builder)", example = "native")
    val source: String,
) {
    @Schema(description = "지표 명세 echo-back")
    data class SpecDto(
        @Schema(description = "지표 ID", example = "rsi")
        val name: String,
        @Schema(description = "파라미터 맵 (문자열 값)", example = """{"period":"14"}""")
        val params: Map<String, String>,
    )

    @Schema(description = "지표 시계열 단건")
    data class PointDto(
        @Schema(description = "캔들 시각 (ISO 8601 UTC)", example = "2024-01-02T00:00:00Z")
        val time: String,
        @Schema(
            description = "지표 값 맵 (문자열) — 단일: {rsi:\"72.34\"}, 복합: {macd:\"0.12\",signal:\"0.10\",histogram:\"0.02\"}",
            example = """{"rsi":"72.34"}""",
        )
        val values: Map<String, String>,
    )

    companion object {
        fun from(result: IndicatorResult): IndicatorResultDto =
            IndicatorResultDto(
                spec =
                    SpecDto(
                        name = result.spec.name,
                        params = result.spec.params.mapValues { it.value.toString() },
                    ),
                series =
                    result.series.map { point ->
                        PointDto(
                            time = point.time.toString(),
                            values = point.values.mapValues { it.value.toPlainString() },
                        )
                    },
                source = result.source,
            )
    }
}
