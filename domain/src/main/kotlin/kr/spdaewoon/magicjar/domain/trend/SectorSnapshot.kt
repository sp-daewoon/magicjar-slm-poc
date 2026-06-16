package kr.spdaewoon.magicjar.domain.trend

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 섹터 1분 snapshot 입력 도메인 (market-data R1 영역의 `sector_index_snapshot` 표 row 1:1 대응).
 *
 * `SectorRankingComposer` 가 본 도메인 list + 일봉 캔들 list 를 입력으로 받아 `SectorRanking` 조립.
 *
 * R1 backend-core 영역은 본 도메인 정의만 (read-only). R1 market-data 가 sector_index_snapshot 영속 + R2 에서
 * `SectorSnapshotReader` adapter (api JPA) 가 본 도메인 조립.
 */
data class SectorSnapshot(
    val sectorCode: String,
    val sectorName: String,
    val ts: Instant,
    val price: BigDecimal,
    val changeRate: BigDecimal,
    val tradeValue: BigDecimal?,
)
