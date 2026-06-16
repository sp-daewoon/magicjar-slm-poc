package kr.spdaewoon.magicjar.domain.market.index.port

import kr.spdaewoon.magicjar.domain.market.index.SectorIndexSnapshot
import java.time.Instant

/**
 * KRX 21 섹터 1분 snapshot 영속 port (ADR 0100 §2-7 V48 정합).
 *
 * 구현체: batch 모듈 `SectorIndexSnapshotJpaAdapter`.
 *
 * 멱등성 (V48 UNIQUE(sector_code, ts)):
 *   - save() 호출이 동일 (sector_code, ts) 행과 충돌 시 ON CONFLICT DO NOTHING
 *
 * Repository default fun 함정 회피 (ADR 0051 + 헌법 §6):
 *   - 본 interface 내부 body 가진 fun 0건
 */
interface SectorIndexSnapshotRepository {
    /**
     * 1분 snapshot 영속 (ON CONFLICT DO NOTHING).
     *
     * @return 신규 insert true · 충돌 skip false
     */
    fun save(snapshot: SectorIndexSnapshot): Boolean

    /**
     * 최신 1건 조회 (sector_code 기준 ts DESC) — ranking composer input.
     */
    fun findLatest(sectorCode: String): SectorIndexSnapshot?

    /**
     * since 이후 모든 snapshot 조회 (sector_code 기준 ts ASC) — ranking 변화율 계산 path 후속.
     */
    fun findSince(
        sectorCode: String,
        since: Instant,
    ): List<SectorIndexSnapshot>

    /**
     * 모든 섹터의 최신 1건 일괄 조회 (cron 마감 후 단일 query — ranking 1회 호출).
     */
    fun findAllLatest(): List<SectorIndexSnapshot>
}
