package kr.spdaewoon.magicjar.consumer.bootstrap

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 부팅 시 Redis market:candle:session:code:{code}:interval:1d HASH stale reset ApplicationRunner.
 *
 * 문제:
 *   - market:candle:session:code:{code}:interval:1d TTL = 24h → Consumer down 후 24h 내 재기동 시 stale HASH 잔존.
 *   - 재기동 후 첫 tick 도착 → KisWebSocketBridge.updateCandleToday 가 stale HASH 위에 누적.
 *   - open 필드 = down 직전 close 값으로 stuck → 당일 1d candle open 부정확.
 *
 * 해결:
 *   - 부팅 시 watchlist 모든 종목의 market:candle:session:code:{code}:interval:1d 키 DEL.
 *   - 다음 tick 도착 시 KisWebSocketBridge.updateCandleToday 가 fresh open으로 정상 누적.
 *
 * 실행 순서: @Order(5) — PositionBootstrapRunner(default @Order) 이후,
 *   CandleBootstrapRunner(@Order(10)) 이전 실행.
 *
 * AI 호출 0 — 룰 5 위반 금지.
 */
@Component
@Order(5)
class RedisCandleTodayResetRunner(
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val redisTemplate: StringRedisTemplate,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(RedisCandleTodayResetRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val entries = watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
        if (entries.isEmpty()) {
            log.info("[RedisCandleTodayReset] watchlist 종목 없음 — reset 건너뜀")
            return
        }

        var deleted = 0
        for (entry in entries) {
            val key = RedisKeyRegistry.Market.candleToday1d(entry.code)
            try {
                val removed = redisTemplate.delete(key)
                if (removed == true) {
                    log.debug("[RedisCandleTodayReset] DEL: key={}", key)
                    deleted++
                }
            } catch (e: Exception) {
                log.warn("[RedisCandleTodayReset] DEL 실패: key={} err={}", key, e.message)
            }
        }

        log.info(
            "[RedisCandleTodayReset] {}종목 reset 완료 (watchlist={}건, DEL={}건)",
            entries.size,
            entries.size,
            deleted,
        )
    }
}
