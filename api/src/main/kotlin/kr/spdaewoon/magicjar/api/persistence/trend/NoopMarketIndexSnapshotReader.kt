package kr.spdaewoon.magicjar.api.persistence.trend

import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * `MarketIndexSnapshotReader` 의 noop 폴백 (R1 market-data 미머지 시점 부팅 정합).
 *
 * R1 market-data 가 `MarketIndexSnapshotJpaAdapter` 발행 시 본 빈은 자동 비활성
 * (`@ConditionalOnMissingBean` 패턴). 사용자 흐름 부팅 ↑ smoke test GREEN 보장.
 *
 * ## mock 합성 0 (feedback_no_mock_data 정합)
 * 본 빈은 mock 데이터를 합성하지 않음 — 빈 list 반환. frontend 는 "데이터 없음" 한국어 표시 의무.
 *
 * @see MarketIndexSnapshotReader (port)
 */
@Component
@ConditionalOnMissingBean(MarketIndexSnapshotReader::class, ignored = [NoopMarketIndexSnapshotReader::class])
class NoopMarketIndexSnapshotReader : MarketIndexSnapshotReader {
    override fun findLatestIndex(code: String): MarketIndexSnapshot? = null

    override fun findLatestIndexes(codes: List<String>): List<MarketIndexSnapshot> = emptyList()

    override fun findDailyIndexSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot> = emptyList()

    override fun findIndexSnapshotSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot> = emptyList()

    override fun findLatestSectorSnapshots(): List<SectorSnapshot> = emptyList()

    override fun findPreviousSectorSnapshots(): List<SectorSnapshot>? = null
}
