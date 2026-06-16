package kr.spdaewoon.magicjar.domain.screener.port

import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import java.time.Duration

/**
 * Screener 결과 캐시 port (ADR 0071 §2-4 D-1 + §5 Redis 키 매트릭스 + spec §2-2).
 *
 * Redis cache 추상 (Phase 2 backend-core `RedisScreenerCacheAdapter` 구현).
 *
 * ### 키 매트릭스 (Phase 2 책임)
 * - `screener:cache:{dslHash}:{universeFilterHash}` TTL 30m — screening 결과 캐시
 * - `screener:lock:{userId}:{strategyId}` TTL 1m — 분산락 SETNX
 *
 * Phase 1: consumer 모듈 ScreenerEngineImpl은 본 port 호출 0 (캐시는 Phase 2 ScreenerService 책임).
 * 본 port는 Phase 2 구현 표면을 미리 LOCK 하기 위해 Phase 1 도메인에 abstract 발행.
 *
 * ### 헌법 §6 정합
 * abstract fun 단독 — body 가진 default fun 0건 LOCK.
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 port + 구현체는 LLM provider import 0 — grep 검증 의무.
 */
interface ScreenerCachePort {
    /**
     * 결과 캐시 조회.
     *
     * @param cacheKey `screener:cache:{dslHash}:{universeFilterHash}` 형식
     * @return cached ScreenerRunResult 또는 null (MISS)
     */
    fun get(cacheKey: String): ScreenerRunResult?

    /**
     * 결과 캐시 저장 (TTL 적용).
     *
     * @param cacheKey 캐시 키
     * @param result 저장할 결과 (Jackson serialize Phase 2 책임)
     * @param ttl TTL — default 30분 (ADR 0071 §5)
     */
    fun put(
        cacheKey: String,
        result: ScreenerRunResult,
        ttl: Duration = Duration.ofMinutes(30),
    )

    /**
     * 분산락 SETNX (ADR 0071 §5 lock 패턴).
     *
     * @param userId 사용자 ID
     * @param strategyId 전략 ID
     * @param ttl 잠금 TTL — default 60초
     * @return true=락 획득 / false=동시 호출 중
     */
    fun tryLock(
        userId: Long,
        strategyId: Long,
        ttl: Duration = Duration.ofSeconds(60),
    ): Boolean

    /**
     * 분산락 해제 (finally 블록 의무).
     */
    fun releaseLock(
        userId: Long,
        strategyId: Long,
    )
}
