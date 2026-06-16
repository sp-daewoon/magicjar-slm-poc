package kr.spdaewoon.magicjar.domain.trend

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * market_index_snapshot · sector_index_snapshot 표 read-only port (ADR 0100 §2-7).
 *
 * 본 port 는 R1 backend-core (본 PR) 가 정의 만 발행. impl 은 R1 market-data 가 별도 PR 로 발행
 * (영역 LOCK — R1 backend-core 는 KIS adapter / market-data JPA entity 0 touch).
 *
 * 본 PR 동일 사이클 까지는 `NoopMarketIndexSnapshotReader` (empty fallback) 로 부팅 가능 →
 * R1 market-data 머지 시 자동 교체 (Spring @Primary 또는 ConditionalOnMissingBean 패턴).
 *
 * ## 함정 회피 (헌법 §6 + ADR 0051)
 * Spring Data JPA Repository 가 아닌 일반 port — body 가진 fun 함정 무관. 단, JPA adapter 발행 시 동일 룰 의무.
 */
interface MarketIndexSnapshotReader {
    /**
     * 지수 단일 최신 snapshot 조회.
     * `code` = KIS index code (0001 · 1001 · 2001 · .IXIC · .SPX 등).
     */
    fun findLatestIndex(code: String): MarketIndexSnapshot?

    /**
     * 4 지수 단일 query (KOSPI · KOSDAQ · NASDAQ · S&P) — MarketIndexController 의 snapshot endpoint 용.
     */
    fun findLatestIndexes(codes: List<String>): List<MarketIndexSnapshot>

    /**
     * 지수별 일 단위 series 조회.
     * 1분 snapshot 테이블에서 KST 날짜별 최신 1건을 골라 오래된 → 최신 순으로 반환한다.
     */
    fun findDailyIndexSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot>

    /**
     * 지수별 원본 snapshot series 조회.
     * 1분 지수 차트는 이 실제 수집값을 시간순으로 받아 OHLC 형태로 파생한다.
     */
    fun findIndexSnapshotSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot>

    /**
     * 섹터 list 최신 snapshot (1분 기준).
     */
    fun findLatestSectorSnapshots(): List<SectorSnapshot>

    /**
     * 섹터 list 직전 1 영업일 snapshot (전일 대비 거래대금 정규화 baseline 용 · null 가능).
     */
    fun findPreviousSectorSnapshots(): List<SectorSnapshot>?
}

/**
 * market_index_snapshot 단일 행 도메인 (read-only DTO).
 *
 * R1 market-data 가 발행할 `MarketIndexSnapshotJpaEntity` ↔ 본 도메인 1:1 변환 의무.
 */
data class MarketIndexSnapshot(
    val code: String,
    val market: String,
    val ts: Instant,
    val price: BigDecimal,
    val changeRate: BigDecimal?,
)
