package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 캔들 백필 동시성 throttle gate + rate-limit circuit breaker
 * (rate-limit safety #3 — 2026-06-04 dogfooding fix · admin 일원화 + circuit breaker — 2026-06-05 readiness P0-1).
 *
 * ## 배경 (운영 evidence 2026-06-04·2026-06-05)
 * - 부팅 직후 `LastCandleAtBootstrapRunner`가 watchlist 8종목을 한꺼번에 `candleBackfillWorker.submit()` →
 *   2-thread pool에서 동시에 1m 페이지네이션 폭주 → KIS TPS_EXCEEDED 알림 폭주. 기존 KIS rate limiter
 *   (Bucket4j 초당 10건)를 통과하더라도, 동시 종목 수가 많으면 알림 임계 위를 장시간 점유.
 * - admin 동기 백필이 본 gate를 "운영자 우회"로 통과하지 않아, admin 1m 8종목 연속 실행 시
 *   ConsumerRedisKisRateLimiterAdapter tpsExceededCount **1→155→337** (2026-06-05 라이브 실측).
 *
 * ## 메커니즘 (기존 백필 설계 위 safety 레이어만 — 보수적)
 * - **직렬화**: `maxConcurrentBackfills` permit semaphore. default 1 (완전 직렬). run 시작 전 acquire.
 * - **run 간 간격**: 직전 run 종료 시각 + `minIntervalMillis` 이전이면 sleep. 백필 burst 분산.
 * - **circuit breaker**: KIS rate-limit 압박(전건 미반환·throttle 거부) run이 연속 `circuitConsecutiveTrips`회면
 *   circuit OPEN → 이후 run은 acquire 전에 즉시 중단(다음 윈도로 이월). `circuitOpenMillis` 경과 후 재진입 허용.
 *
 * ## 적용 범위 (2026-06-05 일원화 — 운영자 우회 폐기)
 * `CandleBackfillWorker.submit()` 비동기 path + admin 동기 `runBackfill()` **둘 다** 본 gate를 통과한다.
 * admin도 KIS 단일 bucket을 공유하므로 throttle·circuit breaker에서 제외하면 폭주 root cause가 남는다.
 *
 * 자본 영향 0 — 데이터 정합 보강 throttle만.
 */
@Component
class BackfillThrottleGate(
    private val chartCandleProperties: ChartCandleProperties,
) {
    private val log = LoggerFactory.getLogger(BackfillThrottleGate::class.java)

    private val cfg get() = chartCandleProperties.backfillGuard

    private val semaphore: Semaphore by lazy {
        Semaphore(cfg.maxConcurrentBackfills.coerceAtLeast(1), true)
    }

    @Volatile
    private var lastRunEndedAtMillis: Long = 0L

    /** rate-limit circuit breaker — 연속 trip 카운터 + OPEN 만료 시각(epoch ms, 0 = CLOSED). */
    private val consecutiveTrips = AtomicInteger(0)
    private val circuitOpenUntilMillis = AtomicLong(0L)

    /**
     * circuit breaker 가드만 적용 (serial semaphore·minInterval 미적용) — block 후 [block] 실행.
     *
     * ## 용도 (ADR 0118 amend — MARKET_DAILY 동시성 보강)
     *   MARKET_DAILY 전종목 일봉 campaign 은 **coroutine Semaphore(permits=N)** 로 동시 종목 수를 제어하고,
     *   KIS hard ceiling 은 `ConsumerRedisKisRateLimiterAdapter`(Bucket4j Redis bucket)가 그대로 유효하다.
     *   따라서 본 gate 의 `maxConcurrentBackfills=1` serial semaphore + 1.5s `minIntervalMillis` 를 적용하면
     *   동시성이 완전히 무력화되고(1종목씩·1.5s 간격) 1.5~2h/run 슬로우 root cause 가 그대로 남는다.
     *   본 메서드는 **circuit breaker(연속 rate-limit trip → OPEN)만** 보존하여 KIS 압박 시 자동 차단은 유지하되,
     *   동시성 제어는 호출자(coroutine Semaphore)에 위임한다. [runThrottled](1m managed-universe path)와 분리.
     *
     * @return [block] 결과, 또는 circuit OPEN 시 null(=run skip — 호출자가 다음 사이클로 미룸).
     */
    fun <T> runWithCircuitGuard(
        code: String,
        block: () -> T,
    ): T? {
        val openUntil = circuitOpenUntilMillis.get()
        if (openUntil > 0L) {
            val now = System.currentTimeMillis()
            if (now < openUntil) {
                log.warn(
                    "BackfillThrottleGate circuit OPEN(circuit-only) — run 즉시 중단: code={} reopenInMs={} trips={}",
                    code,
                    openUntil - now,
                    consecutiveTrips.get(),
                )
                return null
            }
            if (circuitOpenUntilMillis.compareAndSet(openUntil, 0L)) {
                log.info("BackfillThrottleGate circuit half-open(circuit-only) — run 재진입 허용: code={}", code)
            }
        }
        return block()
    }

    /**
     * permit 획득 + run 간 최소 간격 보장 + circuit breaker 가드. block 후 [block] 실행.
     *
     * @return [block] 결과, 또는 permit timeout·circuit OPEN 시 null(=run skip — 호출자가 다음 사이클로 미룸).
     */
    fun <T> runThrottled(
        code: String,
        block: () -> T,
    ): T? {
        // circuit OPEN 가드 — acquire 전에 즉시 중단(다음 윈도 이월). KIS 추가 호출 0.
        val openUntil = circuitOpenUntilMillis.get()
        if (openUntil > 0L) {
            val now = System.currentTimeMillis()
            if (now < openUntil) {
                log.warn(
                    "BackfillThrottleGate circuit OPEN — run 즉시 중단(다음 윈도 이월): code={} reopenInMs={} trips={}",
                    code,
                    openUntil - now,
                    consecutiveTrips.get(),
                )
                return null
            }
            // OPEN 만료 → half-open 재진입 허용 (trip 카운터는 다음 outcome이 reset/재증가).
            if (circuitOpenUntilMillis.compareAndSet(openUntil, 0L)) {
                log.info("BackfillThrottleGate circuit half-open — run 재진입 허용: code={}", code)
            }
        }

        val acquired =
            try {
                semaphore.tryAcquire(cfg.acquireTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!acquired) {
            log.warn(
                "BackfillThrottleGate permit timeout — run skip(다음 사이클로 미룸): code={} timeoutMs={}",
                code,
                cfg.acquireTimeoutMillis,
            )
            return null
        }
        try {
            enforceMinInterval(code)
            return block()
        } finally {
            lastRunEndedAtMillis = System.currentTimeMillis()
            semaphore.release()
        }
    }

    /**
     * run 1건 outcome을 circuit breaker에 보고 (호출자가 [runThrottled] 종료 후 호출).
     *
     * @param rateLimited true = KIS rate-limit 압박(전건 미반환·throttle 거부) 관측 → 연속 trip 카운터 증가,
     *   `circuitConsecutiveTrips` 도달 시 circuit OPEN. false = 정상 회복 → 카운터 reset(false positive 방지).
     */
    fun recordRunOutcome(rateLimited: Boolean) {
        if (!rateLimited) {
            if (consecutiveTrips.getAndSet(0) > 0) {
                log.info("BackfillThrottleGate circuit trip 카운터 reset (정상 회복)")
            }
            return
        }
        val trips = consecutiveTrips.incrementAndGet()
        val threshold = cfg.circuitConsecutiveTrips.coerceAtLeast(1)
        if (trips >= threshold) {
            val openUntil = System.currentTimeMillis() + cfg.circuitOpenMillis.coerceAtLeast(0L)
            circuitOpenUntilMillis.set(openUntil)
            log.warn(
                "BackfillThrottleGate circuit OPEN 발동 — 연속 rate-limit trip {}/{} (openMs={}, 이후 run 다음 윈도 이월)",
                trips,
                threshold,
                cfg.circuitOpenMillis,
            )
        } else {
            log.warn("BackfillThrottleGate circuit trip {}/{} (rate-limit 압박 관측)", trips, threshold)
        }
    }

    /** 테스트·모니터링 — circuit OPEN 상태 여부. */
    fun isCircuitOpen(): Boolean = circuitOpenUntilMillis.get() > System.currentTimeMillis()

    private fun enforceMinInterval(code: String) {
        val minInterval = cfg.minIntervalMillis
        if (minInterval <= 0L) return
        val sinceLast = System.currentTimeMillis() - lastRunEndedAtMillis
        if (lastRunEndedAtMillis > 0L && sinceLast < minInterval) {
            val waitMs = minInterval - sinceLast
            log.debug("BackfillThrottleGate run 간 간격 적용: code={} waitMs={}", code, waitMs)
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
