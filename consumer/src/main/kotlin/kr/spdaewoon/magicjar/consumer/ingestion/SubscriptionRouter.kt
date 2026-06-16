package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Watchlist 시세 공급 라우터 — KIS WS 구독 폐지, 5초 REST polling 전용 (ADR 0124 결정 2).
 *
 * ## 2026-06-13 재편 (ADR 0124)
 * 과거(ADR 0048): watchlist 종목을 KIS WS slot에 선행 구독하고 slot 부족 시 priority eviction →
 * polling 강등하는 WS/polling 혼합 분배기였다.
 *
 * 현재: **watchlist 종목은 처음부터 WS 구독 0**. 등록/부팅 시 바로 [WatchlistPollingWorker] pool에 넣고,
 * 삭제 시 pool에서 제거한다. sourceTracker는 항상 "polling"으로 기록한다(frontend 가격 배지는 시간 기반 판정이라 호환).
 *
 * watchlist WS slot 경합·eviction·promotion 개념 자체가 사라졌다(KIS WS slot 예산은 이제
 * STRATEGY/HOLDING/PENDING_BUY/DETAIL owner demand만 소비 — [RealtimeSubscriptionRouter]가 독립 관리).
 *
 * ## STRATEGY/HOLDING/DETAIL 공존 (불변)
 * 같은 종목이 STRATEGY/HOLDING/DETAIL demand로 WS 구독되는 것은 watchlist 등록 여부와 무관하게 그대로 동작한다.
 * 그 경우 Redis quote는 WS tick이 갱신하므로 watchlist 화면이 더 신선한 값을 읽는다(polling과 공존 무해).
 *
 * - thread safety: ReentrantLock (Kafka listener concurrency=1 + 부팅 reconcile 동시 호출 대응)
 */
@Component
class SubscriptionRouter(
    private val pollingWorker: WatchlistPollingWorker,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
    private val sourceTracker: RedisSourceTrackerAdapter,
) {
    private val log = LoggerFactory.getLogger(SubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    /**
     * watchlist 종목 추가 — polling pool 등록 + source "polling" 기록 (ADR 0124 결정 2).
     * WS 구독 0 — slot 경합·eviction 없음.
     */
    fun add(
        userId: String,
        code: String,
    ) = lock.withLock {
        pollingWorker.add(code)
        sourceTracker.set(userId, code, "polling")
        log.info("watchlist polling 등록: code={} poolSize={}", code, pollingWorker.poolSize())
    }

    /**
     * watchlist 종목 제거 — polling pool 제거 + source 정리 (ADR 0124 결정 2).
     *
     * 같은 종목이 STRATEGY/HOLDING/DETAIL demand로 WS 구독 중이면 그 구독은 [RealtimeSubscriptionRouter]가
     * 독립 관리하므로 여기서 건드리지 않는다(watchlist 제거가 매매·상세 경로 구독을 끊지 않는다).
     */
    fun remove(
        userId: String,
        code: String,
    ) = lock.withLock {
        pollingWorker.remove(code)
        sourceTracker.remove(userId, code)
        log.info("watchlist polling 제거: code={} poolSize={}", code, pollingWorker.poolSize())
    }

    /**
     * 부팅 reconcile or 세션 변경 시 watchlist 전체를 polling pool에 재등록 (ADR 0124 결정 2).
     *
     * WS 구독 0 — watchlist 전 종목을 polling pool에 멱등 등록한다.
     */
    fun recompute(userId: String) =
        lock.withLock {
            val watchlist = watchlistRepository.findByUserIdOrderByPriority(userId)
            if (watchlist.isEmpty()) return@withLock

            watchlist.forEach { entry ->
                pollingWorker.add(entry.code)
                sourceTracker.set(userId, entry.code, "polling")
            }
            log.info(
                "recompute 완료(polling 전용): userId={} polling={}건",
                maskUserId(userId),
                watchlist.size,
            )
        }

    /**
     * 세션 변경 처리 (ADR 0124 결정 2 — polling 전용).
     * Closed 진입 → polling stop + source idle.
     * Regular 복귀 → recompute(polling pool 재등록) + polling start.
     */
    fun onSessionChanged(
        prev: MarketSession,
        current: MarketSession,
    ) = lock.withLock {
        when (current) {
            is MarketSession.Closed -> {
                log.info("장 마감 — watchlist polling 중단")
                pollingWorker.stop()
                watchlistRepository.findAll().forEach { entry ->
                    sourceTracker.set(entry.userId, entry.code, "idle")
                }
            }
            is MarketSession.Regular -> {
                if (prev is MarketSession.Closed) {
                    log.info("장 개시 — watchlist polling 재개")
                    val allUsers = watchlistRepository.findAll().map { it.userId }.distinct()
                    allUsers.forEach { recompute(it) }
                    pollingWorker.start()
                }
            }
            else -> {
                // PreMarket·PostMarket: 1차 단순화 — Regular와 동일 처리.
            }
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
