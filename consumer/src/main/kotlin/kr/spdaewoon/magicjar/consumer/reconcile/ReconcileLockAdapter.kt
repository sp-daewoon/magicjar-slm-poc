package kr.spdaewoon.magicjar.consumer.reconcile

import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Reconcile cron 분산락 + idempotency 캐시 (ADR 0070 §5 Redis 매트릭스).
 *
 * | 키 | TTL | 용도 |
 * |---|---|---|
 * | `reconcile:lock:{userId}` | 5m | cron 동시 실행 방지 (SETNX) |
 * | `reconcile:lastrun:{userId}` | 5m | manual idempotency 캐시 — 5분 내 재호출 시 직전 결과 |
 *
 * Redis 장애 시 — fail-open (lock 미설정으로 cron 진행). 자본 보호 대비 가용성 우선 trade-off (cron 재진입은
 * KIS Rate Limit으로도 안전 cover) — 단 manual trigger는 fail-close 처리하여 자본 사고 회피.
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class ReconcileLockAdapter(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(ReconcileLockAdapter::class.java)

    companion object {
        private val LOCK_TTL: Duration = Duration.ofMinutes(5)
        private val LASTRUN_TTL: Duration = Duration.ofMinutes(5)
    }

    /**
     * cron 분산락 SETNX — 5분 TTL.
     *
     * @return true=취득 성공 (caller가 cron 진행) / false=다른 인스턴스가 이미 처리 중 또는 Redis 장애
     */
    fun acquireLock(userId: String): Boolean =
        try {
            redisTemplate
                .opsForValue()
                .setIfAbsent(RedisKeyRegistry.Reconcile.lock(userId), "1", LOCK_TTL)
                ?: false
        } catch (e: Exception) {
            log.warn(
                "reconcile:lock SETNX 실패 — Redis 장애 가정 (fail-open): userId={} err={}",
                userId,
                e.message,
            )
            // fail-open — cron 진행 (Rate Limit + idempotency 캐시로 회피)
            true
        }

    /**
     * 정상 종료 시 즉시 lock 해제 (TTL fallback OK).
     */
    fun releaseLock(userId: String) {
        try {
            redisTemplate.delete(RedisKeyRegistry.Reconcile.lock(userId))
        } catch (e: Exception) {
            log.warn("reconcile:lock 해제 실패 (TTL fallback): userId={} err={}", userId, e.message)
        }
    }

    /**
     * Manual trigger idempotency 캐시 — 직전 실행 reconcileRunId 반환 시 직전 결과 재사용 권장.
     *
     * @return 직전 실행 runId (있으면 Long 문자열, 없으면 null)
     */
    fun lookupLastRun(userId: String): String? =
        try {
            redisTemplate.opsForValue().get(RedisKeyRegistry.Reconcile.lastRun(userId))
        } catch (e: Exception) {
            log.warn("reconcile:lastrun lookup 실패: userId={} err={}", userId, e.message)
            null
        }

    fun cacheLastRun(
        userId: String,
        runId: Long,
    ) {
        try {
            redisTemplate
                .opsForValue()
                .set(RedisKeyRegistry.Reconcile.lastRun(userId), runId.toString(), LASTRUN_TTL)
        } catch (e: Exception) {
            log.warn("reconcile:lastrun cache 실패: userId={} runId={} err={}", userId, runId, e.message)
        }
    }
}
