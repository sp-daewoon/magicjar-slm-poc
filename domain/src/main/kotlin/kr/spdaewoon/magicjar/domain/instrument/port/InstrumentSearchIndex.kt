package kr.spdaewoon.magicjar.domain.instrument.port

import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult

/**
 * Redis 검색 인덱스 포트 (ADR 0017 §2-4 — T-IM1 PR-11a add-only).
 *
 * 구현체: api/.../infrastructure/redis/InstrumentSearchIndexAdapter (PR-11b backend-core 담당).
 * Redis 키 매트릭스 — pr-11-im1-design.md §6-1 정합:
 *   instrument:search:prefix:{2자prefix}  sorted set  1일 TTL
 *   instrument:by-code:{code}             JSON String  1일 TTL
 *   instrument:list:{market}              JSON List    1일 TTL
 */
interface InstrumentSearchIndex {
    /**
     * 전체 인덱스 재구성 (부팅 시 + sync 후).
     */
    fun rebuild(instruments: List<Instrument>)

    /**
     * 전체 invalidate (sync 시작 전 또는 완료 후 호출).
     * instrument:search:prefix:* + instrument:list:* DEL
     */
    fun invalidate()

    /**
     * 종목 검색 (Redis prefix bucket — 2자 bucket 기반).
     * Redis miss → DB fallback은 service layer 책임.
     */
    fun search(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult>
}
