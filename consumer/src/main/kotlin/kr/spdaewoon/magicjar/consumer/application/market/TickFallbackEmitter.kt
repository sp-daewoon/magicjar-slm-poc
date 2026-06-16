package kr.spdaewoon.magicjar.consumer.application.market

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 틱 폴백 에미터 (ADR 0014 Fallback 3-tier, PR-29a).
 *
 * 역할:
 *   T1 (KIS WS live): KisWebSocketBridge에서 직접 publish
 *   T2 (Redis cache): 10초 주기 — KIS WS 신호 없을 때 캐시 값으로 stale tick publish
 *   T3 (last-day-close): session=Closed 시 — DailyCloseFinalizer 영속 1d candle 값으로 publish
 *
 * 동작:
 *   10초 주기 @Scheduled → 구독 종목 목록 순회 → quote:last:{code} 조회
 *   → stale 여부 + session 상태 포함 market.tick.v1 publish
 *
 * ADR 0014 §2 stale 표시:
 *   - stale: true 시 응답에 포함
 *   - asOf: 마지막 틱 타임스탬프
 *   - source: "REDIS_T2" | "DB_T3_LAST_DAY"
 */
@Component
class TickFallbackEmitter(
    private val marketSessionResolver: MarketSessionResolver,
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(TickFallbackEmitter::class.java)

    companion object {
        private const val TOPIC_TICK = KafkaTopics.Market.TICK
        private const val MAX_STALE_SECONDS = 30L // 30초 이상 신호 없으면 stale
    }

    /**
     * 10초 주기 T2 폴백 publish (ADR 0014 §2).
     * session=Closed 시 추가 T3 로직 포함 (DailyCloseFinalizer가 영속한 값 사용).
     */
    @Scheduled(fixedDelay = 10_000L)
    fun emitFallback() {
        val now = java.time.Instant.now()
        val ktNow = kotlinx.datetime.Instant.fromEpochMilliseconds(now.toEpochMilli())
        val session = marketSessionResolver.resolve(ktNow)

        // Closed 세션은 T2 폴백도 생략 (KIS WS 끊김 정상 — STOMP frontend가 stale 배지만 표시)
        if (session is MarketSession.Closed) {
            log.trace("TickFallbackEmitter skip: session=Closed")
            return
        }

        // TODO: 구독 종목 목록은 SubscriptionRouter에서 노출 필요 — PR-29a 범위 외
        // 현재 구현은 구조 stub. 실제 구독 종목 순회는 후속 PR 또는 SubscriptionRouter.activeSubscriptions() 노출 후 활성화.
        log.trace("TickFallbackEmitter: session={} — 구독 종목 순회 (stub)", session::class.simpleName)
    }
}
