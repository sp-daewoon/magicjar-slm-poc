package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [EntryQuotePort] 합성 구현 — ADR 0114 §10-3 R2-C1.
 *
 * 우선순위(§10-3 ① → ②):
 * 1. **Redis 호가 캐시**([RedisOrderbookTopCache]) — H0UNASP0 WS write-through `orderbook:top:{code}`.
 * 2. cache miss 시 **KIS REST 1회**([KisRestAskingPriceAdapter], `FHKST01010200`).
 * 3. 둘 다 실패 → **null**(호가 미가용 = 진입 스킵. 시장가 fallback 금지는 상위 EntryPricePolicy).
 *
 * **stale 캐시는 본 어댑터가 직접 거르지 않는다** — EntryPricePolicy가 `quoteAsOf`(=EntryQuote.asOf) 기반으로
 * stale(default 3초)을 판정해 진입을 스킵하므로, 캐시 hit이면 asOf를 그대로 넘긴다(단일 stale gate 책임 — 중복 판정 회피).
 * 단 Redis 캐시 TTL(10초)이 만료되면 read가 null을 반환하므로 자연히 REST로 넘어간다.
 */
@Component
class CompositeEntryQuoteAdapter(
    private val redisOrderbookTopCache: RedisOrderbookTopCache,
    private val kisRestAskingPriceAdapter: KisRestAskingPriceAdapter,
) : EntryQuotePort {
    private val log = LoggerFactory.getLogger(CompositeEntryQuoteAdapter::class.java)

    override fun latestQuote(code: String): EntryQuote? {
        redisOrderbookTopCache.read(code)?.let { cached ->
            log.debug("EntryQuote cache hit: code={} ask1={} bid1={} asOf={}", code, cached.ask1, cached.bid1, cached.asOf)
            return cached
        }
        // cache miss(미구독 종목·TTL 만료·파싱 실패) → REST 1회 fallback.
        val rest = kisRestAskingPriceAdapter.fetchTopQuote(code)
        if (rest == null) {
            log.info("EntryQuote 미가용(cache miss + REST 실패): code={} — 진입 스킵 예정", code)
        } else {
            log.debug("EntryQuote REST fallback hit: code={} ask1={} bid1={}", code, rest.ask1, rest.bid1)
        }
        return rest
    }
}
