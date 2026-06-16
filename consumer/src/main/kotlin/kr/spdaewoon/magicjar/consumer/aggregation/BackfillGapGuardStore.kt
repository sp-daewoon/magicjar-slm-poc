package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 캔들 백필 회복 불가 갭 가드 store (rate-limit safety #1 — 2026-06-04 dogfooding fix).
 *
 * ## 배경 (운영 evidence 2026-06-04)
 * consumer 재배포 부팅 → watchlist 8종목 1m 2일 stale 감지 → 자동 백필 8건 트리거 →
 * `CandleBackfill 실패 — failed=5008/5008 fatal=true` 전건 실패. 실패해도 last_candle_at이
 * 채워지지 않아 **다음 부팅/모니터 사이클마다 같은 수천 봉을 재시도** → KIS rate limit 폭주 무한 루프
 * (16:31 6건 → 16:50 73건/5분 TPS_EXCEEDED).
 *
 * ## 핵심 구분 (mock 합성 금지 — feedback_no_mock_data.md)
 * - 갭 row 자체는 채우지 않는다. 갭은 갭으로 남긴다.
 * - "회복 불가"로 판정된 (code, interval)에 대해 **재시도만 영구 중단**한다.
 * - KIS `FHKST03010230` 보관 한도 = 최대 1년 (KIS-first raw 인용:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *   "Maximum 1 year of minute candles are retained"). 1년 밖 또는 N회 연속 KIS 전건 미반환 갭은
 *   재시도 대상에서 제외.
 *
 * ## Redis 키 (기존 인프라 재사용 — 신규 테이블 회피, 헌법 §6 Repository 함정 회피)
 * - `candle:backfill:guard:miss:{code}:{interval}` — 연속 전건 미반환(total-miss) run 카운터. TTL [guardTtlHours].
 * - `candle:backfill:guard:unrecoverable:{code}:{interval}` — 영구 회복 불가 마킹. TTL [unrecoverableTtlHours]
 *   (영구지만 무한 누적 회피 위해 장기 TTL — 만료 후 1회 재확인 허용).
 *
 * Redis 재사용 사유: 부팅마다 같은 갭 재시도 차단이 목적이므로 **reboot 생존**이 필수.
 * in-memory(`CandleReconciliationStateStore`)는 JVM lifetime 내 fail-closed 용도라 reboot에 소실.
 *
 * 자본 영향 0 — 데이터 정합 가드만. fail-open(Redis 장애 시 기존 동작 보존).
 */
@Component
class BackfillGapGuardStore(
    private val redisTemplate: StringRedisTemplate,
    private val chartCandleProperties: ChartCandleProperties,
) {
    private val log = LoggerFactory.getLogger(BackfillGapGuardStore::class.java)

    private val cfg get() = chartCandleProperties.backfillGuard

    private fun missKey(
        code: String,
        interval: CandleInterval,
    ): String = RedisKeyRegistry.Market.candleBackfillMiss(code, interval.code)

    private fun unrecoverableKey(
        code: String,
        interval: CandleInterval,
    ): String = RedisKeyRegistry.Market.candleBackfillUnrecoverable(code, interval.code)

    /**
     * (code, interval)가 회복 불가로 영구 마킹되어 백필 재시도를 건너뛰어야 하는가.
     *
     * fail-open: Redis 장애 시 false(=재시도 허용) — 가드 부재가 기존 동작이므로 회귀 0.
     */
    fun isUnrecoverable(
        code: String,
        interval: CandleInterval,
    ): Boolean =
        try {
            redisTemplate.hasKey(unrecoverableKey(code, interval))
        } catch (e: Exception) {
            log.warn("BackfillGapGuard isUnrecoverable Redis 조회 실패 — fail-open: code={} err={}", code, e.message)
            false
        }

    /**
     * KIS 전건 미반환(total-miss) run 1회 기록 + 연속 카운터 증가.
     *
     * @return 이번 기록 후 (code, interval)가 회복 불가 임계치에 도달했는지 여부.
     *   true → 호출자가 unrecoverable 마킹 + 1회 terminal 알림 발화 + 재시도 중단.
     */
    fun recordTotalMissAndShouldGiveUp(
        code: String,
        interval: CandleInterval,
    ): Boolean {
        val threshold = cfg.consecutiveTotalMissThreshold
        return try {
            val key = missKey(code, interval)
            val count = redisTemplate.opsForValue().increment(key) ?: 1L
            redisTemplate.expire(key, Duration.ofHours(cfg.guardTtlHours.toLong()))
            log.warn(
                "BackfillGapGuard total-miss 기록: code={} interval={} consecutive={}/{}",
                code,
                interval.code,
                count,
                threshold,
            )
            count >= threshold
        } catch (e: Exception) {
            // fail-open: Redis 장애 시 give-up 하지 않음 (기존 fatal 동작 보존)
            log.warn("BackfillGapGuard total-miss 기록 실패 — fail-open: code={} err={}", code, e.message)
            false
        }
    }

    /**
     * (code, interval) 회복 불가 영구 마킹 + 연속 카운터 reset.
     * 호출 시점: [recordTotalMissAndShouldGiveUp]가 true 반환 시 또는 KIS 보관 한도(1년) 밖 갭 확정 시.
     */
    fun markUnrecoverable(
        code: String,
        interval: CandleInterval,
        reason: String,
    ) {
        try {
            redisTemplate.opsForValue().set(
                unrecoverableKey(code, interval),
                reason,
                Duration.ofHours(cfg.unrecoverableTtlHours.toLong()),
            )
            redisTemplate.delete(missKey(code, interval))
            log.warn(
                "BackfillGapGuard UNRECOVERABLE 마킹: code={} interval={} reason={} ttlHours={}",
                code,
                interval.code,
                reason,
                cfg.unrecoverableTtlHours,
            )
        } catch (e: Exception) {
            log.warn("BackfillGapGuard markUnrecoverable Redis 실패: code={} err={}", code, e.message)
        }
    }

    /**
     * 정상 백필(갭 일부라도 회복) 시 연속 total-miss 카운터를 reset.
     * 회복이 한 번이라도 발생하면 회복 불가 누적을 0으로 되돌려 false positive 영구 마킹 방지.
     */
    fun recordRecovery(
        code: String,
        interval: CandleInterval,
    ) {
        try {
            redisTemplate.delete(missKey(code, interval))
        } catch (e: Exception) {
            log.debug("BackfillGapGuard recordRecovery reset 실패(무시): code={} err={}", code, e.message)
        }
    }
}
