package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.ingestion.SubscriptionRouter
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * kafka.api.evt.watchlist.changed.v1 Kafka consumer (ADR 0028·0048 §2-6 §4 정합).
 *
 * - groupId: magicjar-consumer-watchlist-ingestion
 * - manual ack (MANUAL_IMMEDIATE — ADR 0045 graceful shutdown 정합)
 * - at-least-once delivery + idempotent 처리
 * - action=ADDED: watchlist pool 등록 (시세 자동 조회 X — ADR 0124 amend 2026-06-15)
 * - action=REMOVED: watchlist pool 제거 + Redis evict
 *
 * ★ watchlist WS 구독 폐지 (ADR 0124 · 2026-06-13):
 *   watchlist 종목은 처음부터 KIS WS 구독 0 — 5초 REST polling 전용이다(SubscriptionRouter가 polling pool만 관리).
 *   같은 종목이 STRATEGY/HOLDING/DETAIL demand로 WS 구독 중이면 그 구독은 RealtimeSubscriptionRouter가
 *   독립 관리하므로 watchlist 제거가 그 경로를 끊지 않는다(공존 무해).
 *
 * ★ proactive cold-start 백필 폐지 (ADR 0119 · 2026-06-09):
 *   watchlist 종목 추가 시 과거 1m·일봉 무더기 KIS 백필을 더 이상 트리거하지 않는다.
 *   신규 종목의 일봉은 전종목 일봉 universe(R-BF/R2 · ADR 0118)가 이미 보유하고,
 *   과거 1m 정밀 갭은 차트 진입 on-demand(SymbolDetailMinuteGapFillService) 또는 admin 수동 backfill만 수행.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class WatchlistChangedKafkaListener(
    private val objectMapper: ObjectMapper,
    private val subscriptionRouter: SubscriptionRouter,
    private val redisQuoteCacheAdapter: RedisQuoteCacheAdapter,
    private val redisSourceTracker: RedisSourceTrackerAdapter,
) {
    private val log = LoggerFactory.getLogger(WatchlistChangedKafkaListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.System.WATCHLIST_CHANGED],
        groupId = "magicjar-consumer-watchlist-ingestion",
        containerFactory = "watchlistChangedKafkaListenerContainerFactory",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        val event =
            try {
                objectMapper.readValue<WatchlistChangedEvent>(record.value())
            } catch (e: Exception) {
                log.error("{} 파싱 실패 — offset={} err={}", KafkaTopics.System.WATCHLIST_CHANGED, record.offset(), e.message)
                // parse 실패는 Dead Letter로 보낼 수 없으므로 ack + 스킵
                ack.acknowledge()
                return
            }

        log.info(
            "WatchlistChangedKafkaListener 수신: userId={} code={} action={}",
            maskUserId(event.userId),
            event.code,
            event.action,
        )

        try {
            when (event.action) {
                "ADDED" -> handleAdded(event)
                "REMOVED" -> handleRemoved(event)
                else -> log.warn("알 수 없는 action: {} — 무시", event.action)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            // 예외 시 ack 호출 안 함 → 재처리 (idempotent 보장)
            log.error(
                "{} 처리 실패 (재처리 대상) — code={} action={} err={}",
                KafkaTopics.System.WATCHLIST_CHANGED,
                event.code,
                event.action,
                e.message,
                e,
            )
        }
    }

    private fun handleAdded(event: WatchlistChangedEvent) {
        val code = event.code
        val userId = event.userId

        // 1. SubscriptionRouter에 위임 — watchlist는 WS 구독 0, pool 등록 전용 (ADR 0124 결정 2).
        //    proactive cold-start 백필 폐지 (ADR 0119): 신규 종목 일봉=R-BF/R2 기보유, 1m=차트 진입 on-demand.
        subscriptionRouter.add(userId, code)

        // 2. Redis quote:last warm 없음.
        //    ADR 0124 amend(2026-06-15): watchlist 시세 5초 REST polling 폐지(KIS quota 보호).
        //    quote:last 는 더 이상 watchlist 경로로 채우지 않는다. STRATEGY/DETAIL 종목은 STOMP WS tick 직접 수신.

        log.info("ADDED 처리 완료: code={}", code)
    }

    private fun handleRemoved(event: WatchlistChangedEvent) {
        val code = event.code
        val userId = event.userId

        // 1. SubscriptionRouter에 위임 (unsubscribe or polling remove + promotion 재계산)
        subscriptionRouter.remove(userId, code)

        // 2. Redis quote:last evict (ADR 0014 §4-3 — 1차 MVP: 즉시 DELETE)
        redisQuoteCacheAdapter.evict(code)

        // 3. Redis watchlist:source evict
        redisSourceTracker.remove(userId, code)

        log.info("REMOVED 처리 완료: code={}", code)
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
