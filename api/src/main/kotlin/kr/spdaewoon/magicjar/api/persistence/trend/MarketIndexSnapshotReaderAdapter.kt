package kr.spdaewoon.magicjar.api.persistence.trend

import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.trend.MarketIndexSnapshotReader
import kr.spdaewoon.magicjar.domain.trend.SectorSnapshot
import org.springframework.stereotype.Component
import kotlinx.datetime.Instant as KxInstant

/**
 * market_index_snapshot / sector_index_snapshot read adapter.
 *
 * 배치는 1분 snapshot 을 저장하고, API는 이 테이블을 직접 읽어 시장지수/섹터 화면에 노출한다.
 * Candle 테이블은 종목 OHLCV 용이므로 지수 카드의 단일 진실원으로 쓰지 않는다.
 */
@Component
class MarketIndexSnapshotReaderAdapter(
    private val indexRepository: MarketIndexSnapshotJpaRepository,
    private val sectorRepository: SectorIndexSnapshotJpaRepository,
) : MarketIndexSnapshotReader {
    override fun findLatestIndex(code: String): MarketIndexSnapshot? = indexRepository.findLatestByCode(code)?.toDomain()

    override fun findLatestIndexes(codes: List<String>): List<MarketIndexSnapshot> {
        if (codes.isEmpty()) return emptyList()
        val latestByCode = indexRepository.findLatestByCodes(codes).associateBy { it.code }
        return codes.mapNotNull { latestByCode[it]?.toDomain() }
    }

    override fun findDailyIndexSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot> {
        if (limit <= 0) return emptyList()
        return indexRepository.findDailySeries(code, limit).map { it.toDomain() }
    }

    override fun findIndexSnapshotSeries(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot> {
        if (limit <= 0) return emptyList()
        return indexRepository.findSnapshotSeries(code, limit).map { it.toDomain() }
    }

    override fun findLatestSectorSnapshots(): List<SectorSnapshot> = sectorRepository.findLatestBySector().map { it.toDomain() }

    override fun findPreviousSectorSnapshots(): List<SectorSnapshot>? =
        sectorRepository
            .findPreviousBusinessDayBySector()
            .map { it.toDomain() }
            .takeIf { it.isNotEmpty() }

    private fun MarketIndexSnapshotJpaEntity.toDomain(): MarketIndexSnapshot =
        MarketIndexSnapshot(
            code = code,
            market = market,
            ts = ts.toKotlinInstant(),
            price = price,
            changeRate = changeRate,
        )

    private fun SectorIndexSnapshotJpaEntity.toDomain(): SectorSnapshot =
        SectorSnapshot(
            sectorCode = sectorCode,
            sectorName = sectorName,
            ts = ts.toKotlinInstant(),
            price = price,
            changeRate = changeRate,
            tradeValue = tradeValue,
        )

    private fun java.time.Instant.toKotlinInstant(): KxInstant = KxInstant.parse(toString())
}
