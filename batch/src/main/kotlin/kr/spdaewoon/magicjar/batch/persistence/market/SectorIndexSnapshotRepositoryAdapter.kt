package kr.spdaewoon.magicjar.batch.persistence.market

import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import kr.spdaewoon.magicjar.domain.market.index.port.SectorIndexSnapshotRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * SectorIndexSnapshotRepository port 구현 (batch 모듈 — ADR 0100 §2-7 정합).
 */
@Component
class SectorIndexSnapshotRepositoryAdapter(
    private val jpaRepository: SectorIndexSnapshotJpaRepository,
) : SectorIndexSnapshotRepository {
    override fun save(snapshot: SectorIndexSnapshot): Boolean {
        val inserted =
            jpaRepository.upsert(
                sectorCode = snapshot.sectorCode,
                sectorName = snapshot.sectorName,
                ts = snapshot.ts,
                price = snapshot.price,
                changeRate = snapshot.changeRate,
                tradeValue = snapshot.tradeValue,
                rawPayload = snapshot.rawPayload,
            )
        return inserted > 0
    }

    override fun findLatest(sectorCode: String): SectorIndexSnapshot? =
        jpaRepository
            .findLatestByCode(sectorCode, PageRequest.of(0, 1))
            .firstOrNull()
            ?.toDomain()

    override fun findSince(
        sectorCode: String,
        since: Instant,
    ): List<SectorIndexSnapshot> = jpaRepository.findSince(sectorCode, since).map { it.toDomain() }

    override fun findAllLatest(): List<SectorIndexSnapshot> = jpaRepository.findAllLatest().map { it.toDomain() }

    private fun SectorIndexSnapshotJpaEntity.toDomain(): SectorIndexSnapshot =
        SectorIndexSnapshot(
            sectorCode = this.sectorCode,
            sectorName = this.sectorName,
            ts = this.ts,
            price = this.price,
            changeRate = this.changeRate,
            tradeValue = this.tradeValue,
            rawPayload = this.rawPayload,
        )
}
