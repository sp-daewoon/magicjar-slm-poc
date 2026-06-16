package kr.spdaewoon.magicjar.domain.market.index.port

import kr.spdaewoon.magicjar.domain.market.index.MarketIndexSnapshot
import java.time.Instant

/**
 * 지수·섹터 1분 snapshot 영속 port (ADR 0100 §2-7 V47 정합).
 *
 * 구현체: batch 모듈 `MarketIndexSnapshotJpaAdapter` (api·consumer 모듈은 read-only 조회 path 후속).
 *
 * 멱등성 (V47 UNIQUE(code, ts)):
 *   - save() 호출이 동일 (code, ts) 행과 충돌 시 ON CONFLICT DO NOTHING (1분 cron drift 흡수)
 *
 * Repository default fun 함정 회피 (ADR 0051 + 헌법 §6):
 *   - 본 interface 내부 body 가진 fun 0건 — 구현체에서 native UPSERT 발행
 */
interface MarketIndexSnapshotRepository {
    /**
     * 1분 snapshot 영속 (ON CONFLICT DO NOTHING).
     *
     * @return 신규 insert true · 충돌 skip false
     */
    fun save(snapshot: MarketIndexSnapshot): Boolean

    /**
     * 최신 N건 조회 (code 기준 ts DESC).
     *
     * @param code 지수 코드
     * @param limit 조회 건수 (default 1)
     */
    fun findLatest(
        code: String,
        limit: Int = 1,
    ): List<MarketIndexSnapshot>

    /**
     * since 이후 모든 snapshot 조회 (code 기준 ts ASC) — 차트 백오프 fallback path 후속.
     */
    fun findSince(
        code: String,
        since: Instant,
    ): List<MarketIndexSnapshot>
}
