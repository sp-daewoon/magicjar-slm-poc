package kr.spdaewoon.magicjar.batch.infrastructure.kis.index

import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
import kr.spdaewoon.magicjar.domain.market.index.IndexCode
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexDaily
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.KisIndexFetcher
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * KisIndexFetcher port 단일 통합 impl — 5 adapter 위임 (ADR 0100 §2-3 정합).
 *
 * 책임 분리:
 *   - 본 class는 분기 라우팅만 (category → adapter dispatch)
 *   - 비즈니스 결정·KIS 호출은 5 adapter 각자
 *
 * caller (batch MarketIndexSyncJob · MarketIndexDailyCloseJob)는 본 port 단일 진입.
 */
@Component
class KisIndexFetcherAdapter(
    private val domesticIndex: KisDomesticIndexAdapter,
    private val domesticDaily: KisDomesticIndexDailyAdapter,
    private val domesticSector: KisDomesticSectorRankingAdapter,
    private val overseasIndex: KisOverseasIndexAdapter,
    private val overseasDaily: KisOverseasIndexDailyAdapter,
) : KisIndexFetcher {
    override fun fetchDomesticIndex(code: IndexCode): MarketIndexSnapshot? {
        require(code.category != IndexCategory.OVERSEAS_INDEX) {
            "fetchDomesticIndex는 OVERSEAS_INDEX 호출 금지 — fetchOverseasIndex 사용"
        }
        return domesticIndex.fetch(code)
    }

    override fun fetchDomesticIndexDaily(
        code: IndexCode,
        baseDate: LocalDate,
        periodDiv: String,
    ): List<MarketIndexDaily> {
        require(code.category != IndexCategory.OVERSEAS_INDEX) {
            "fetchDomesticIndexDaily는 OVERSEAS_INDEX 호출 금지 — fetchOverseasIndexDaily 사용"
        }
        return domesticDaily.fetch(code, baseDate, periodDiv)
    }

    override fun fetchDomesticSectorRanking(
        marketClsCode: String,
        belongClsCode: String,
    ): List<SectorIndexSnapshot> = domesticSector.fetch(marketClsCode, belongClsCode)

    override fun fetchOverseasIndex(code: IndexCode): MarketIndexSnapshot? = overseasIndex.fetch(code)

    override fun fetchOverseasIndexDaily(
        code: IndexCode,
        baseDate: LocalDate,
        modp: String,
    ): List<MarketIndexDaily> = overseasDaily.fetch(code, baseDate, modp)
}
