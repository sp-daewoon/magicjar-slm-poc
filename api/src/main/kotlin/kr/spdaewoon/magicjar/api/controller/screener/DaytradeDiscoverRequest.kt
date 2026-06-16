package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive

/**
 * 단타 발굴(Discovery) 요청 DTO (ADR 0117 §2-3 + spec §3-R2).
 *
 * `POST /api/screener/daytrade-discover` request body.
 *
 * ### 기존 screener evaluate 와의 차이 (충돌 회피 — spec §1-1)
 * - `evaluate`는 `strategyId` 필수 — 사용자가 미리 저장한 DSL을 평가.
 * - `daytrade-discover`는 `strategyId` 없음 — 시스템 빌트인 단타 setup 프리셋(V75 시드)으로 동작.
 *   `presetKey`가 null이면 활성 `DAYTRADE_*` 빌트인 프리셋 전체를 합쳐 발굴한다.
 *
 * ### 룰 기반 LOCK (ADR 0117 §2-3)
 * 본 발굴은 룰 기반(KIS raw + DSL) — LLM 호출 0. `/api/strategy-research/ai-discovery`(LLM)와 개념·path 분리.
 *
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "단타 발굴(룰 기반·LLM 0) 요청 — 시스템 빌트인 단타 setup 프리셋으로 오늘의 후보 TOP N 발굴")
data class DaytradeDiscoverRequest(
    @Schema(description = "사용자 ID (multi-tenant 격리 — 빌트인 프리셋 평가도 userId 검증)", example = "12345678", required = true)
    @field:NotNull
    @field:Positive
    val userId: Long,
    @Schema(
        description = "단타 setup 프리셋 key (null이면 활성 DAYTRADE_* 빌트인 프리셋 전체 합집합 발굴). V75 시드 row의 candidate_key",
        example = "DAYTRADE_GAP_RVOL_TURNOVER",
    )
    val presetKey: String? = null,
    @Schema(description = "universe 필터 (옵션 — 기본 KOSPI+KOSDAQ active STOCK+ETF)")
    val filters: ScreenerFilterDto? = null,
    @Schema(
        description = "평가 기준봉 (장전 setup = 일봉 1d 기본; 분봉도 데이터 있으면 가능하나 발굴 표준은 1d)",
        example = "1d",
    )
    val interval: String = "1d",
    @Schema(description = "발굴 TOP N (1~100, default 30 — 발굴은 압축된 후보 풀)", example = "30")
    @field:Min(1)
    @field:Max(100)
    val limit: Int = 30,
    @Schema(
        description = "장중 감시 시작 — true면 발굴 후보를 STRATEGY owner WS 수요로 등록(watchlist write 0). false면 발굴 결과만 반환",
        example = "false",
    )
    val startRealtimeWatch: Boolean = false,
)
