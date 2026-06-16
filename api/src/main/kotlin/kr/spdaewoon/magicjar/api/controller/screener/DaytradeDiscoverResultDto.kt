package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 단타 발굴(Discovery) 결과 DTO (ADR 0117 §2-3 + spec §3-R2).
 *
 * `POST /api/screener/daytrade-discover` response body.
 *
 * 기존 [ScreenerResultDto] 형태를 재사용하고 발굴 메타(`presets`·`isBuiltin`·`realtimeWatchRegistered`)를 덧붙인다.
 *
 * ### 주문 트리거 필드 0 LOCK (spec §1-2)
 * 발굴은 후보 **출발점**이지 매매 결정이 아니다. 본 DTO는 주문/자동매매 트리거 필드를 일절 갖지 않는다.
 * `matches`는 사용자가 검증·저장·스크리닝을 시작하는 후보 목록일 뿐이다.
 *
 * Swagger @Schema 4단 LOCK (사용자 메모리 feedback_swagger_pattern 정합).
 */
@Schema(description = "단타 발굴 결과 + 랭킹 (후보 출발점 — 자동매매 결정권 0)")
data class DaytradeDiscoverResultDto(
    @Schema(description = "발굴에 사용된 빌트인 프리셋 메타 목록", required = true)
    val presets: List<DaytradeDiscoverPresetDto>,
    @Schema(description = "전부 시스템 빌트인 프리셋 여부 (항상 true — 발굴은 시스템 소유 프리셋만 사용)", example = "true")
    val isBuiltin: Boolean,
    @Schema(description = "universe 사이즈 (필터 후 종목 수)", example = "2847")
    val universeSize: Int,
    @Schema(description = "평가 완료 수 (skip 제외)", example = "2814")
    val evaluatedCount: Int,
    @Schema(description = "발굴 후보(매칭) 수", example = "23")
    val matchedCount: Int,
    @Schema(description = "skip 사유별 카운트 (캔들 미수집·부족·평가 오류 — mock 합성 0, 정직 표기)", example = """{"캔들 미수집": 12, "캔들 부족": 21}""")
    val skippedReasons: Map<String, Int>,
    @Schema(description = "랭킹된 발굴 후보 (limit 적용, descending score)")
    val matches: List<ScreenerMatchDto>,
    @Schema(description = "전체 발굴 elapsed (ms)", example = "4521")
    val elapsedMs: Long,
    @Schema(
        description = "장중 감시 STRATEGY owner 등록 종목 수 (startRealtimeWatch=true일 때만 >0). watchlist write는 항상 0",
        example = "0",
    )
    val realtimeWatchRegistered: Int,
)

/**
 * 발굴에 사용된 빌트인 프리셋 메타 (UI 표시 + 후보 출처 추적).
 */
@Schema(description = "발굴에 사용된 빌트인 단타 setup 프리셋 메타")
data class DaytradeDiscoverPresetDto(
    @Schema(description = "프리셋 key (V75 시드 candidate_key)", example = "DAYTRADE_GAP_RVOL_TURNOVER")
    val presetKey: String,
    @Schema(description = "프리셋 제목", example = "장전 갭상승·RVOL·거래대금 setup")
    val title: String,
    @Schema(description = "사람이 읽는 진입 조건 목록", example = """["전일 종가 대비 1.5% 이상 갭상승","거래량이 최근 20봉 평균의 2.5배 이상(RVOL)"]""")
    val entryRules: List<String>,
)
