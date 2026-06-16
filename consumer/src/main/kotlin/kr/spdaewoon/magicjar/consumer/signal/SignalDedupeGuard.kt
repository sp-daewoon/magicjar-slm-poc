package kr.spdaewoon.magicjar.consumer.signal

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 시그널 중복 발행 가드 (ADR 0066 §2-5 E-1 채택 + §7-1 SignalDedupeGuard).
 *
 * 사용자 결정 (2026-05-12): E-1 DB UQ + Redis SETNX 30s window 보조.
 * 본 컴포넌트는 Redis 보조 — DB UQ는 Phase 2 backend-core가 발행.
 *
 * ### 동작
 * Redis SETNX(`set if not exists`) 30초 TTL:
 *   key = `signal:dedupe:strategy:{strategyId}:code:{code}:interval:{interval}:ts:{ts}`
 *   value = "1"
 *   TTL = 30s
 *
 * boundary closing ts는 deterministic(ADR 0064 §4-2) — 동일 boundary 평가 시
 * 첫 번째 호출만 `tryAcquire`가 true 반환 → 후속 호출은 false → 평가 skip.
 *
 * ### TTL 30s 근거 (ADR 0066 §2-5 E-1)
 * boundary closing(5m=300s, 15m=900s) 대비 충분히 짧음 — 동일 boundary 중복 검출만 cover.
 * 다음 boundary는 새 ts 키이므로 무관.
 *
 * ### Phase 2 DB UQ 정합
 * Redis SETNX 실패(DUPLICATE_KEY) 후에도 DB INSERT 시 UQ 위반 발생 가능
 *   (예: Redis TTL 만료 후 컨테이너 재기동) — 구현체는 `log.debug` + skip 의무.
 *
 * spec 인용: ADR 0066 §2-5 E-1 + §7-1 + §7-2 단계 1.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class SignalDedupeGuard(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(SignalDedupeGuard::class.java)

    companion object {
        private val TTL = Duration.ofSeconds(30)
    }

    /**
     * 멱등 키 획득 시도 (SETNX).
     *
     * @return true — 첫 번째 발행 (평가 진행 의무) · false — 이미 처리됨 (skip 의무)
     */
    fun tryAcquire(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        ts: Instant,
    ): Boolean {
        val key = buildKey(strategyId, code, interval, ts)
        return try {
            val acquired =
                redisTemplate
                    .opsForValue()
                    .setIfAbsent(key, "1", TTL)
                    ?: false
            if (!acquired) {
                log.debug(
                    "SignalDedupeGuard dup skipped: strategyId={} code={} interval={} ts={}",
                    strategyId,
                    code,
                    interval.code,
                    ts,
                )
            }
            acquired
        } catch (e: Exception) {
            // Redis 결함 시 fail-open — DB UQ가 최종 보호망 (ADR 0066 §2-5 E-1 정합)
            log.warn(
                "SignalDedupeGuard Redis 실패 — fail-open: strategyId={} code={} err={}",
                strategyId,
                code,
                e.message,
            )
            true
        }
    }

    /** `signal:dedupe:strategy:{strategyId}:code:{code}:interval:{interval}:ts:{ts.epochSeconds}` */
    private fun buildKey(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        ts: Instant,
    ): String = RedisKeyRegistry.Signal.dedupe(strategyId, code, interval.code, ts.epochSeconds)
}
