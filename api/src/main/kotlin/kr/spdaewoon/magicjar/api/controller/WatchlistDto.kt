package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.time.Instant

/**
 * 관심종목 등록 요청 (spec §4-1-2 LOCK).
 * 6자리 숫자 검증 — 옵션 C 채택 (raw code 저장, T-IM1에서 보강).
 */
@Schema(description = "관심종목 등록 요청")
data class WatchlistAddRequest(
    @field:Pattern(regexp = "^\\d{6}$", message = "종목 코드는 6자리 숫자여야 합니다 (예: 005930)")
    @Schema(description = "KIS 종목 코드 6자리 (KOSPI·KOSDAQ·ETF·ETN)", example = "005930")
    val code: String,
    @Schema(description = "우선순위 (낮은 값 = 높은 우선순위, null = 0 기본)", example = "0", nullable = true)
    val priority: Int? = null,
)

/**
 * 관심종목 응답 DTO (spec §4-1-1 LOCK).
 *
 * PR-21b 보강: type·securitiesGroup·marketCapPrev·tradingHalt·managementStock
 *             industryMedium·isKospi200·isKosdaq150 추가.
 * - type: 사용자가 ETF/우선주/REIT 즉시 식별 가능 (KIS scrt_grp_cls_code 기반)
 * - marketCapPrev: watchlist row에 시총 표시 (사용자 직관)
 * - tradingHalt·managementStock: 매매 위험 가드 (즉시 인지)
 * - industryMedium: 업종 중분류 표시
 * instrument 테이블 V5 미적용 또는 sync 전: 신규 필드 모두 null/false graceful 처리.
 */
@Schema(description = "관심종목 응답")
data class WatchlistEntryDto(
    @Schema(description = "관심종목 ID", example = "1")
    val id: Long,
    @Schema(description = "사용자 ID (KIS 계좌번호 8자리)", example = "74683223")
    val userId: String,
    @Schema(description = "KIS 종목 코드 6자리", example = "005930")
    val code: String,
    @Schema(description = "종목명 (instrument 테이블 join — 미동기화 시 null)", example = "삼성전자", nullable = true)
    val name: String? = null,
    @Schema(description = "시장 구분 (KOSPI·KOSDAQ·KONEX·UNKNOWN)", example = "KOSPI", nullable = true)
    val market: String? = null,
    @Schema(description = "우선순위 (낮은 값 = 높은 우선순위)", example = "0")
    val priority: Int,
    @Schema(description = "등록 시각 (UTC ISO-8601)", example = "2026-05-07T10:23:45.123Z")
    val createdAt: Instant,
    // PR-21b 신규 필드 — KIS-first 보강 (사용자 가시성)
    // instrument V5 sync 완료 후 채워짐. 미완료 시 null/false graceful.
    @Schema(
        description = "종목 유형 (STOCK·ETF·ETN·PREFERRED·REIT·WARRANT·DR·UNKNOWN)",
        example = "STOCK",
        nullable = true,
    )
    val type: String? = null,
    @Schema(
        description = "KIS 증권그룹구분코드 (scrt_grp_cls_code). ST·MF·RT·SC·IF·DR·EW·EF·SW·SR·BC·FE·FS",
        example = "ST",
        nullable = true,
    )
    val securitiesGroup: String? = null,
    @Schema(
        description = "전일기준 시가총액 억 단위 (market_cap_prev). T-SC 대형주 분류용.",
        example = "4150000",
        nullable = true,
    )
    val marketCapPrev: Long? = null,
    @Schema(description = "거래정지 여부. true 시 매매 차단 필요 (KIS trht_yn).", example = "false")
    val tradingHalt: Boolean = false,
    @Schema(description = "관리종목 여부. 투자 위험 가드 (KIS mang_issu_yn).", example = "false")
    val managementStock: Boolean = false,
    @Schema(
        description = "지수업종 중분류 코드 (bstp_medm_div_code). 업종 필터용.",
        example = "G251",
        nullable = true,
    )
    val industryMedium: String? = null,
    @Schema(description = "KOSPI200 편입 여부.", example = "true")
    val isKospi200: Boolean = false,
    @Schema(description = "KOSDAQ150 편입 여부.", example = "false")
    val isKosdaq150: Boolean = false,
    @Schema(
        description =
            "상장폐지 여부 (instrument.active=false). " +
                "true 시 사용자에게 '상장폐지' 작은 라벨 표시 — 사용자가 직접 watchlist에서 제거 결정.",
        example = "false",
    )
    val delisted: Boolean = false,
)
