package kr.spdaewoon.magicjar.batch.persistence.market

import kr.spdaewoon.magicjar.domain.market.index.IndexCategory
import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.MarketIndexSnapshotRepository
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * MarketIndexSnapshotRepository port 구현 (batch 모듈 — ADR 0100 §2-7 정합).
 *
 * 변환 규약 (헌법 §6 Repository default fun 회피):
 *   - 본 adapter는 일반 class — body 가진 private fun 자유 (Repository interface 본문 아님)
 */
@Component
class MarketIndexSnapshotRepositoryAdapter(
    private val jpaRepository: MarketIndexSnapshotJpaRepository,
) : MarketIndexSnapshotRepository {
    override fun save(snapshot: MarketIndexSnapshot): Boolean {
        val inserted =
            jpaRepository.upsert(
                code = snapshot.code,
                market = snapshot.category.name,
                ts = snapshot.ts,
                price = snapshot.price,
                open = null,
                high = null,
                low = null,
                close = null,
                changeRate = snapshot.changeRate,
                volume = snapshot.volume,
                rawPayload = snapshot.rawPayload,
            )
        return inserted > 0
    }

    override fun findLatest(
        code: String,
        limit: Int,
    ): List<MarketIndexSnapshot> = jpaRepository.findLatest(code, limit).map { it.toDomain() }

    override fun findSince(
        code: String,
        since: Instant,
    ): List<MarketIndexSnapshot> = jpaRepository.findSince(code, since).map { it.toDomain() }

    private fun MarketIndexSnapshotJpaEntity.toDomain(): MarketIndexSnapshot =
        MarketIndexSnapshot(
            code = this.code,
            category = runCatching { IndexCategory.valueOf(this.market) }.getOrElse { IndexCategory.DOMESTIC_INDEX },
            ts = this.ts,
            price = this.price,
            changeRate = this.changeRate,
            volume = this.volume,
            rawPayload = this.rawPayload,
        )
}
