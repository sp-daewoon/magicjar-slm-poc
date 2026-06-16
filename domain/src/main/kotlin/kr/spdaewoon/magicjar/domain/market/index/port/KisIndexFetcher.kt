package kr.spdaewoon.magicjar.domain.market.index.port

import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import java.time.LocalDate

/**
 * 지수·섹터 KIS fetcher 단일 진입점 (ADR 0100 §2-3 KIS endpoint 5 LOCK 정합).
 *
 * ### 구현체 5건 (batch 모듈)
 * - KisDomesticIndexAdapter           — FHPUP02100000 (`fetchDomesticIndex`)
 * - KisDomesticIndexDailyAdapter      — FHPUP02120000 (`fetchDomesticIndexDaily`)
 * - KisDomesticSectorRankingAdapter   — FHPUP02140000 (`fetchDomesticSectorRanking`)
 * - KisOverseasIndexAdapter           — HHDFS00000300 (`fetchOverseasIndex`)
 * - KisOverseasIndexDailyAdapter      — HHDFS76240000 (`fetchOverseasIndexDaily`)
 *
 * ### 의존 방향 (ADR 0004 헥사고날)
 * - 본 port는 domain 모듈 LOCK (Spring·webflux·JPA 의존 0)
 * - 구현체는 batch 모듈 `infrastructure.kis.index.*Adapter`
 *
 * ### KIS-first raw fetch 의무 (헌법 §3 + ADR 0100 §3-2)
 * 구현체 5건 모두 inline KDoc 상단에 raw URL + tr_id 명시 의무.
 *
 * ### Rate Limit 정합 (ADR 0037 amend 2026-05-24 INGESTION_LOW)
 * 구현체는 acquire(INGESTION_LOW) 의무 호출. Phase 2 우선순위 큐 적용 시 가장 먼저 drop.
 */
interface KisIndexFetcher {
    /**
     * 국내 지수·업종 1건 현재가 fetch (FHPUP02100000).
     *
     * @param code DOMESTIC_INDEX·DOMESTIC_SECTOR enum value (예: IndexCode.KOSPI · KOSPI_SECTORS[0])
     * @return null 시 KIS 응답 부재/실패 — caller batch Job이 graceful skip
     */
    fun fetchDomesticIndex(code: IndexCode): MarketIndexSnapshot?

    /**
     * 국내 지수·업종 일봉 fetch (FHPUP02120000).
     *
     * @param code 국내 분류 IndexCode
     * @param baseDate 기준일 (YYYYMMDD) — KIS가 baseDate 이전 일봉 array 반환
     * @param periodDiv "D"/"W"/"M" — 일·주·월 (default D)
     * @return 일봉 array (최신순). 빈 list = 응답 부재
     */
    fun fetchDomesticIndexDaily(
        code: IndexCode,
        baseDate: LocalDate,
        periodDiv: String = "D",
    ): List<MarketIndexDaily>

    /**
     * 국내 섹터 ranking 1회 호출 — 전 KOSPI 섹터 동시 반환 (FHPUP02140000).
     *
     * tr_cont M/F pagination 재귀 처리 (max 10 page LOCK).
     *
     * @param marketClsCode "K"(거래소)·"Q"(KOSDAQ)·"K2"(KOSPI200) — default "K"
     * @param belongClsCode "0"(전체) — default "0"
     * @return SectorIndexSnapshot list (KOSPI 21 업종 정합 정렬)
     */
    fun fetchDomesticSectorRanking(
        marketClsCode: String = "K",
        belongClsCode: String = "0",
    ): List<SectorIndexSnapshot>

    /**
     * 해외 지수 현재가 fetch (HHDFS00000300).
     *
     * @param code OVERSEAS_INDEX IndexCode (.IXIC · .SPX 정합 — exchange 필수)
     * @return null 시 KIS 응답 부재/실패
     */
    fun fetchOverseasIndex(code: IndexCode): MarketIndexSnapshot?

    /**
     * 해외 지수 일봉 fetch (HHDFS76240000).
     *
     * tr_cont M/F pagination 재귀 처리 (max 10 page LOCK).
     *
     * @param code OVERSEAS_INDEX IndexCode
     * @param baseDate 기준일 (YYYYMMDD) — KIS가 baseDate 이전 일봉 반환
     * @param modp "0"(원본)·"1"(수정주가) — default "1"
     */
    fun fetchOverseasIndexDaily(
        code: IndexCode,
        baseDate: LocalDate,
        modp: String = "1",
    ): List<MarketIndexDaily>
}
