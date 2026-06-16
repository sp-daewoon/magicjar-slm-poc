package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 발굴 스코어카드 조회 응답 DTO (Flyway V77).
 *
 * `GET /api/screener/scorecard` response body — 기간·프리셋별 forward-test 요약 + 상세 rows.
 *
 * ### 발굴 품질 hit-rate
 * 백테스트(과거)와 달리 매일 장전 발굴 TOP N을 다음 거래일 실제 일봉으로 채점해 쌓은 살아있는 증거다.
 * `hitRatePct` = 채점된 후보 중 next_return_pct > 0 비율 (발굴이 진짜 좋은 종목을 뽑는가).
 *
 * Swagger @Schema 4단 LOCK (feedback_swagger_pattern 정합). 매매 트리거 필드 0 — 관찰·기록만.
 */
@Schema(description = "발굴 스코어카드 — 기간·프리셋별 forward-test 요약 + 상세 (자동매매 결정권 0)")
data class DiscoveryScorecardResponseDto(
    @Schema(description = "조회 시작일 (포함)", example = "2026-05-10")
    val from: LocalDate,
    @Schema(description = "조회 종료일 (포함)", example = "2026-06-10")
    val to: LocalDate,
    @Schema(description = "프리셋 필터 (null이면 전체 DAYTRADE_* 프리셋)", example = "DAYTRADE_GAP_RVOL_TURNOVER")
    val presetKey: String?,
    @Schema(description = "전체 요약 (모든 프리셋 합산)")
    val overall: DiscoveryScorecardSummaryDto,
    @Schema(description = "프리셋별 요약")
    val byPreset: List<DiscoveryScorecardSummaryDto>,
    @Schema(description = "상세 rows (snapshot_date DESC · rank ASC)")
    val rows: List<DiscoveryScorecardRowDto>,
)

/**
 * 발굴 hit-rate 요약 (전체 또는 프리셋 단위).
 */
@Schema(description = "발굴 forward-test 요약 통계")
data class DiscoveryScorecardSummaryDto(
    @Schema(description = "프리셋 key (전체 요약이면 null)", example = "DAYTRADE_GAP_RVOL_TURNOVER")
    val presetKey: String?,
    @Schema(description = "기록된 발굴 후보 표본 수 (미채점 포함)", example = "420")
    val sampleCount: Int,
    @Schema(description = "채점 완료 표본 수 (next_return_pct 계산됨)", example = "390")
    val scoredCount: Int,
    @Schema(description = "채점 표본의 평균 다음날 종가 수익률 % (null이면 채점 0건)", example = "0.83")
    val avgNextReturnPct: BigDecimal?,
    @Schema(description = "채점 표본의 평균 다음날 시초가 수익률 %", example = "0.21")
    val avgNextOpenReturnPct: BigDecimal?,
    @Schema(description = "hit rate % = 채점 표본 중 next_return_pct > 0 비율 (null이면 채점 0건)", example = "57.4")
    val hitRatePct: BigDecimal?,
)

/**
 * 발굴 스코어카드 상세 row.
 */
@Schema(description = "발굴 스코어카드 상세 row (1 발굴 후보 = 1 row)")
data class DiscoveryScorecardRowDto(
    @Schema(description = "발굴 발사일 (= 채점 대상 거래일)", example = "2026-06-09")
    val snapshotDate: LocalDate,
    @Schema(description = "발굴 프리셋 key", example = "DAYTRADE_GAP_RVOL_TURNOVER")
    val presetKey: String,
    @Schema(description = "랭킹", example = "1")
    val rank: Int,
    @Schema(description = "종목 코드", example = "005930")
    val instrumentCode: String,
    @Schema(description = "종목명", example = "삼성전자")
    val instrumentName: String,
    @Schema(description = "발굴 점수", example = "0.854321")
    val score: BigDecimal,
    @Schema(description = "발굴 입력(전일) 종가 = 수익률 분모", example = "71000")
    val baseClose: BigDecimal?,
    @Schema(description = "다음날 시가", example = "71500")
    val nextOpen: BigDecimal?,
    @Schema(description = "다음날 고가", example = "72800")
    val nextHigh: BigDecimal?,
    @Schema(description = "다음날 종가", example = "72200")
    val nextClose: BigDecimal?,
    @Schema(description = "다음날 종가 수익률 %", example = "1.69")
    val nextReturnPct: BigDecimal?,
    @Schema(description = "다음날 시초가 수익률 %", example = "0.70")
    val nextOpenReturnPct: BigDecimal?,
    @Schema(description = "채점 시각 (null=미채점 — 일봉 미수집 등, mock 0)", example = "2026-06-09T14:30:00Z")
    val scoredAt: Instant?,
)
