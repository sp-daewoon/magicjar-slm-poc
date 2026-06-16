package kr.spdaewoon.magicjar.api.service.screener

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerEvaluateRequest
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerMatchDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerResultDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreeningRunDetailDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreeningRunDto
import kr.spdaewoon.magicjar.api.persistence.screener.ScreeningRunRepositoryAdapter
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.screener.ScreenerCriteria
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerRunResult
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerCachePort
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerEngine
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.time.Instant

/**
 * Screener 서비스 (ADR 0071 §2-4 D-1 + §5 + spec §2-3).
 *
 * 분산락 + 캐시 lookup + ScreenerEngine 호출 + 옵션 영속 (D-2).
 *
 * 흐름:
 * 1. 캐시 키 조립 (strategy + 기준봉 interval hash + universeFilterHash)
 * 2. 분산락 SETNX — 동시 호출 방지
 * 3. 캐시 HIT 시 즉시 반환 (sub-second)
 * 4. MISS → ScreenerEngine.screen(criteria)
 * 5. 결과 Redis 캐시 PUT (TTL 30m)
 * 6. 상위 후보를 watchlist가 아닌 STRATEGY owner 실시간 관찰 수요로 발행
 * 7. 옵션 영속 ON 시 screening_run + screening_match INSERT
 * 8. finally 분산락 해제
 *
 * userId 격리 (ADR 0010 multi-tenant 정합).
 *
 * AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5).
 * KIS endpoint 호출 0 LOCK.
 */
@Service
class ScreenerService(
    private val screenerEngine: ScreenerEngine,
    private val screenerCachePort: ScreenerCachePort,
    private val screeningRunRepositoryAdapter: ScreeningRunRepositoryAdapter,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ScreenerService::class.java)

    /**
     * screening 평가 실행.
     *
     * @param request 평가 요청 DTO
     * @return 결과 DTO (캐시 HIT 시 cacheHit=true)
     * @throws ResponseStatusException 409 — 분산락 충돌 + 캐시 부재 동시 호출
     * @throws ResponseStatusException 500 — DSL 평가 엔진 오류
     */
    fun evaluate(request: ScreenerEvaluateRequest): ScreenerResultDto {
        val filter = buildFilter(request)
        val interval = parseInterval(request.interval)
        val dslHash = sha256Short("strategy:${request.strategyId}:interval:${interval.code}")
        val filterHash = buildFilterHash(filter)
        val cacheKey = RedisKeyRegistry.Screener.result(dslHash, filterHash)

        // 1. 분산락 SETNX
        val locked = screenerCachePort.tryLock(request.userId, request.strategyId)
        if (!locked) {
            // 동시 호출 — 직전 캐시 fetch 시도
            val cached = screenerCachePort.get(cacheKey)
            if (cached != null) {
                log.info(
                    "[screener] 분산락 충돌 + 캐시 HIT: userId={} strategyId={} interval={}",
                    request.userId,
                    request.strategyId,
                    interval.code,
                )
                publishStrategyRealtimeDemands(cached, request, interval)
                return toDto(cached)
            }
            log.warn(
                "[screener] 분산락 충돌 + 캐시 MISS: userId={} strategyId={} interval={}",
                request.userId,
                request.strategyId,
                interval.code,
            )
            throw ResponseStatusException(HttpStatus.CONFLICT, "동시 평가 중 — 잠시 후 재시도")
        }

        try {
            // 2. 캐시 HIT 확인
            val cached = screenerCachePort.get(cacheKey)
            if (cached != null) {
                log.debug("[screener] cache HIT: key={}", cacheKey)
                publishStrategyRealtimeDemands(cached, request, interval)
                return toDto(cached)
            }

            // 3. ScreenerEngine inline 평가
            log.info(
                "[screener] evaluate START: userId={} strategyId={} interval={} limit={}",
                request.userId,
                request.strategyId,
                interval.code,
                request.limit,
            )
            val criteria =
                ScreenerCriteria(
                    userId = request.userId,
                    strategyId = request.strategyId,
                    filter = filter,
                    interval = interval,
                    limit = request.limit,
                )
            val result =
                runCatching { screenerEngine.screen(criteria) }.getOrElse { ex ->
                    log.error(
                        "[screener] ScreenerEngine 오류: userId={} strategyId={} interval={} err={}",
                        request.userId,
                        request.strategyId,
                        interval.code,
                        ex.message,
                    )
                    throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DSL 평가 엔진 오류: ${ex.message}")
                }

            // 4. 캐시 PUT (TTL 30m)
            val resultWithKey = result.copy(cacheKey = cacheKey, cacheHit = false)
            screenerCachePort.put(cacheKey, resultWithKey)

            // 5. 전략 후보 실시간 관찰 수요 발행 (watchlist 자동 등록 아님)
            publishStrategyRealtimeDemands(resultWithKey, request, interval)

            // 6. 옵션 영속
            persistIfEnabled(resultWithKey, request, filterHash, dslHash)

            log.info(
                "[screener] evaluate DONE: userId={} strategyId={} interval={} universeSize={} matchedCount={} elapsedMs={}",
                request.userId,
                request.strategyId,
                interval.code,
                result.universeSize,
                result.matchedCount,
                result.elapsedMs,
            )
            return toDto(resultWithKey)
        } finally {
            screenerCachePort.releaseLock(request.userId, request.strategyId)
        }
    }

    /**
     * screening 실행 이력 조회 (옵션 영속 ON 시).
     * persistEnabled=false 시 empty page.
     */
    fun listRuns(
        userId: Long,
        page: Int,
        size: Int,
    ): Page<ScreeningRunDto> = screeningRunRepositoryAdapter.listRuns(userId, page, size)

    /**
     * screening 단건 상세 조회 (옵션 영속 ON 시).
     * persistEnabled=false 또는 userId mismatch 시 null.
     */
    fun findDetail(
        id: Long,
        userId: Long,
    ): ScreeningRunDetailDto? = screeningRunRepositoryAdapter.findDetail(id, userId)

    // ── private helpers ──────────────────────────────────────────────────

    private fun buildFilter(request: ScreenerEvaluateRequest): ScreenerFilter {
        val dto = request.filters ?: return ScreenerFilter.DEFAULT
        val markets =
            dto.markets
                ?.mapNotNull { runCatching { InstrumentMarket.valueOf(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: ScreenerFilter.DEFAULT_MARKETS

        val types =
            dto.types
                ?.mapNotNull { runCatching { InstrumentType.valueOf(it) }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: ScreenerFilter.DEFAULT_TYPES

        return ScreenerFilter(
            markets = markets,
            types = types,
            marketCapMin = dto.marketCapMin,
            avgVolumeMin = dto.avgVolumeMin,
        )
    }

    private fun parseInterval(interval: String): CandleInterval =
        runCatching { CandleInterval.fromCode(interval) }.getOrElse {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 interval: $interval (허용: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            )
        }

    private fun buildFilterHash(filter: ScreenerFilter): String {
        val raw = "${filter.markets.sorted()}:${filter.types.sorted()}:${filter.marketCapMin}:${filter.avgVolumeMin}"
        return sha256Short(raw)
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun persistIfEnabled(
        result: ScreenerRunResult,
        request: ScreenerEvaluateRequest,
        filterHash: String,
        dslHash: String,
    ) {
        runCatching {
            screeningRunRepositoryAdapter.persist(
                result = result,
                userId = request.userId,
                strategyId = request.strategyId,
                universeFilterHash = filterHash,
                dslHash = dslHash,
            )
        }.onFailure { ex ->
            log.warn("[screener] 영속 저장 실패 (무시 — cache-only 계속): err={}", ex.message)
        }
    }

    private fun publishStrategyRealtimeDemands(
        result: ScreenerRunResult,
        request: ScreenerEvaluateRequest,
        interval: CandleInterval,
    ) {
        val topMatches = result.matches.take(request.limit.coerceIn(1, STRATEGY_REALTIME_DEMAND_MAX))
        if (topMatches.isEmpty()) return

        topMatches.forEachIndexed { index, match ->
            runCatching {
                val payload =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "action" to "ENTER",
                            "owner" to "STRATEGY",
                            // bare ownerId — prefix(`strategy:`)는 consumer 스냅샷 직렬화에서 1회 부여(이중 prefix 회피).
                            "ownerId" to "${request.strategyId}",
                            "code" to match.instrumentCode,
                            "priority" to (STRATEGY_REALTIME_PRIORITY_BASE + index),
                            "ttlSeconds" to STRATEGY_REALTIME_TTL_SECONDS,
                            "strategyId" to request.strategyId,
                            "rank" to match.rank,
                            "interval" to interval.code,
                            "cacheKey" to result.cacheKey,
                            "ts" to Instant.now().toString(),
                        ),
                    )
                kafkaTemplate.send(KafkaTopics.Market.REALTIME_DEMAND, match.instrumentCode, payload)
            }.onFailure { ex ->
                log.warn(
                    "[screener] realtime demand 발행 실패 (무시): strategyId={} code={} err={}",
                    request.strategyId,
                    match.instrumentCode,
                    ex.message,
                )
            }
        }
        log.info(
            "[screener] realtime demand 발행: strategyId={} interval={} count={}",
            request.strategyId,
            interval.code,
            topMatches.size,
        )
    }

    private fun toDto(result: ScreenerRunResult): ScreenerResultDto =
        ScreenerResultDto(
            cacheKey = result.cacheKey,
            universeSize = result.universeSize,
            evaluatedCount = result.evaluatedCount,
            matchedCount = result.matchedCount,
            skippedReasons = result.skippedReasons,
            matches = result.matches.map { toMatchDto(it) },
            elapsedMs = result.elapsedMs,
            cacheHit = result.cacheHit,
        )

    private fun toMatchDto(match: ScreenerMatch): ScreenerMatchDto =
        ScreenerMatchDto(
            rank = match.rank,
            instrumentCode = match.instrumentCode,
            instrumentName = match.instrumentName,
            score = match.score,
            scoreComponents = match.breakdown.toComponentMap(),
            reason = match.reason,
        )

    private companion object {
        const val STRATEGY_REALTIME_DEMAND_MAX = 30
        const val STRATEGY_REALTIME_PRIORITY_BASE = 40
        const val STRATEGY_REALTIME_TTL_SECONDS = 30L * 60L
    }
}
