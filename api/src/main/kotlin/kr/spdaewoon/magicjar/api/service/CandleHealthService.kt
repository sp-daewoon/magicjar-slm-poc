package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import kotlinx.datetime.Instant as KxInstant

/**
 * 캔들 수집 건강도(candle health) 판정 서비스 — readiness P1 (2026-06-04 평가 §B-1a).
 *
 * ## 배경 (readiness 평가 P1 — docs/assessments/2026-06-04-autotrading-readiness.html)
 * "현재는 last candle이 늦다는 결과는 볼 수 있지만, 원인이 WS 미구독·tick 없음·DB insert 실패·
 * KIS 백필 실패·장외 정상 상태 중 무엇인지 사용자 화면에서 바로 알기 어렵다."
 * 완료 기준: "화면에서 '왜 수집 안 됐는지'가 종목별 1줄로 표시".
 *
 * ## 모듈 경계 (ADR 0085 · ADR 0088 정합)
 * frontend는 api 모듈만 호출한다. consumer의 KIS WS / 백필 워커는 판정 데이터를
 * **공유 인프라(Redis·DB)** 에 이미 기록하고 있으므로, api 모듈에서 그 원천을 직접 읽어
 * 판정을 어셈블한다. consumer → api HTTP 호출은 신설하지 않는다(모듈 의존 LOCK).
 *
 * 읽기 전용 원천 (모두 consumer가 이미 기록 — 본 서비스는 읽기만):
 * - DB `candle` 테이블 last ts ([CandleJpaRepository.findMaxTsByCodeAndInterval]) — STALE/NO_DATA 1차 판정.
 * - Redis `candle:current:{code}:{interval}` hash `updatedAt`·`source` — 장중 진행 봉 마지막 tick 시각.
 *   (KisWebSocketBridge.updateLiveCandleHash 가 매 tick 기록 — consumer/ws/KisWebSocketBridge.kt)
 * - Redis `quote:last:{code}` JSON `ts`·`source` — 최근 tick 도착 시각 + WS/polling 구분.
 *   (KisWebSocketBridge.writeToRedis 가 매 tick 기록)
 * - Redis `watchlist:source:{userId}` hash — 종목별 "live"(WS)·"polling"·"idle" 수집 source.
 *   (RedisSourceTrackerAdapter — consumer/infrastructure/cache)
 * - Redis `candle:backfill:guard:unrecoverable:{code}:{interval}` — 영구 회복 불가 갭 마킹.
 *   (BackfillGapGuardStore.markUnrecoverable — consumer/aggregation)
 * - Redis `candle:backfill:guard:miss:{code}:{interval}` — 연속 백필 전건 미반환 카운터(임계치 미달 = 진행 중 실패).
 * - [MarketSessionResolver] — KRX 영업일·세션 판정 (api KrxMarketSessionResolver bean).
 *
 * ## 판정 우선순위 트리 (KDoc 명문화 — 신규 개발자 reason 분기 혼동 방지)
 *
 * 1. **lastCandleAt** = max(DB last ts, Redis current `updatedAt`) — 둘 다 없으면 null.
 * 2. **staleSeconds** = now - lastCandleAt (lastCandleAt null이면 null).
 * 3. **status** 판정:
 *    - lastCandleAt == null                       → `NO_DATA`
 *    - staleSeconds ≤ freshThreshold(interval)     → `FRESH`
 *    - else                                        → `STALE`
 * 4. **reason** 판정 (FRESH 이외일 때 — 우선순위 순서로 첫 매칭 채택):
 *    - FRESH                                       → `OK_REALTIME`
 *    - 장 마감/장외(session != Regular)            → `MARKET_CLOSED`   (장외 stale는 정상 — 최우선)
 *    - unrecoverable Redis 키 present              → `BACKFILL_PERMANENT_GAP` (KIS 보관 한도 밖 — 재수집 불가)
 *    - miss counter Redis 키 present(임계치 미달)   → `BACKFILL_FAILED` (백필 재시도 중 실패 누적)
 *    - 종목이 어떤 user에도 "live"(WS) source 아님   → `WS_NOT_SUBSCRIBED` (실시간 구독 자체 부재)
 *    - WS 구독 중인데 최근 tick 없음                → `NO_TICK`         (구독은 했으나 체결 미도착)
 *    - 그 외                                       → `UNKNOWN`         (mock 합성 금지 — 정직 반환)
 *
 * **MARKET_CLOSED 최우선 사유**: 장외 시간에는 stale이 정상 상태이므로, 다른 실패 reason보다 먼저
 * 판정해 false alarm(WS_NOT_SUBSCRIBED·NO_TICK)을 억제한다. 장 개시 후에야 실패 원인이 의미를 가진다.
 *
 * **INSERT_FAILED 미구현 사유**: DB insert 실패는 현재 별도 Redis 마킹이 없다(워커 in-memory fatal flag).
 * 본 P1 범위에서 워커 수정(CandleBackfillWorker LOCK)을 피하므로, insert 실패는 결과적으로 BACKFILL_FAILED
 * (miss counter) 또는 STALE+UNKNOWN으로 귀결된다. enum 값은 계약 호환을 위해 정의만 유지(반환 0).
 *
 * 자본 영향 0 — 데이터 정합 관측만. 모든 Redis 조회 fail-open(장애 시 UNKNOWN).
 * mock 합성 금지(feedback_no_mock_data.md) — 판정 불가 시 UNKNOWN 정직 반환.
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class CandleHealthService(
    private val candleJpaRepository: CandleJpaRepository,
    private val redisTemplate: StringRedisTemplate,
    private val marketSessionResolver: MarketSessionResolver,
) {
    private val log = LoggerFactory.getLogger(CandleHealthService::class.java)

    companion object {
        /** 기본 점검 interval — 분봉이 실시간 수집 파이프라인의 1차 권위 (장중 tick 합산). */
        const val DEFAULT_INTERVAL = "1m"

        /**
         * interval별 FRESH 임계치(초). 초과 시 STALE.
         *
         * 1m은 정규장 중 1분마다 새 봉 + tick 마다 current 갱신 → 마진 포함 180초.
         * 상위 timeframe은 봉 주기가 길어 임계치도 비례 확대.
         * 미정의 interval은 [DEFAULT_FRESH_THRESHOLD_SECONDS].
         */
        private val FRESH_THRESHOLD_SECONDS: Map<String, Long> =
            mapOf(
                "1m" to 180L,
                "3m" to 360L,
                "5m" to 600L,
                "15m" to 1_200L,
                "30m" to 2_400L,
                "90m" to 6_000L,
                "1h" to 4_800L,
                "4h" to 18_000L,
                "12h" to 50_400L,
                "1d" to 100_800L, // ≈ 28h (1 거래일 + 마감 마진)
                "1wk" to 691_200L, // ≈ 8일
                "1mo" to 3_456_000L, // ≈ 40일
                "1y" to 34_560_000L, // ≈ 400일
            )

        private const val DEFAULT_FRESH_THRESHOLD_SECONDS = 180L

        /**
         * 연속 백필 전건 미반환 임계치 — 이 값 이상이면 unrecoverable로 승격됨(consumer BackfillGapGuardStore).
         * api는 miss counter가 이 임계치 *미만*으로 present일 때 "재시도 중 실패(BACKFILL_FAILED)"로 판정.
         * consumer `magicjar.consumer.candle.backfill-guard.consecutive-total-miss-threshold` default(3) 정합.
         */
        private const val BACKFILL_MISS_GIVE_UP_THRESHOLD = 3L

        val SUPPORTED_INTERVALS =
            setOf("1m", "3m", "5m", "15m", "30m", "90m", "1h", "4h", "12h", "1d", "1wk", "1mo", "1y")
    }

    /** 캔들 건강도 status. */
    enum class CandleHealthStatus {
        FRESH,
        STALE,
        NO_DATA,
    }

    /**
     * 캔들 수집 정체 reason — 계약 LOCK enum (orchestrator API 계약 정합).
     * INSERT_FAILED는 계약 호환 위해 정의만 유지(현재 판정 미발행 — KDoc 참조).
     */
    enum class CandleHealthReason {
        OK_REALTIME,
        MARKET_CLOSED,
        WS_NOT_SUBSCRIBED,
        NO_TICK,
        INSERT_FAILED,
        BACKFILL_FAILED,
        BACKFILL_PERMANENT_GAP,
        UNKNOWN,
    }

    data class CandleHealth(
        val code: String,
        val interval: String,
        val lastCandleAt: Instant?,
        val staleSeconds: Long?,
        val status: CandleHealthStatus,
        val reason: CandleHealthReason,
        val reasonDetail: String,
        val checkedAt: Instant,
    )

    /**
     * 단일 종목 캔들 건강도 판정.
     *
     * @param code 6자리 종목코드
     * @param interval 점검 interval (default [DEFAULT_INTERVAL])
     */
    fun assess(
        code: String,
        interval: String = DEFAULT_INTERVAL,
    ): CandleHealth {
        val now = Instant.now()
        val effectiveInterval = if (interval in SUPPORTED_INTERVALS) interval else DEFAULT_INTERVAL

        val lastCandleAt = resolveLastCandleAt(code, effectiveInterval)
        val staleSeconds = lastCandleAt?.let { (now.epochSecond - it.epochSecond).coerceAtLeast(0L) }
        val threshold = FRESH_THRESHOLD_SECONDS[effectiveInterval] ?: DEFAULT_FRESH_THRESHOLD_SECONDS

        val status =
            when {
                lastCandleAt == null -> CandleHealthStatus.NO_DATA
                staleSeconds != null && staleSeconds <= threshold -> CandleHealthStatus.FRESH
                else -> CandleHealthStatus.STALE
            }

        val session = resolveSession(now)
        val (reason, detail) = resolveReason(code, effectiveInterval, status, session, staleSeconds, now)

        return CandleHealth(
            code = code,
            interval = effectiveInterval,
            lastCandleAt = lastCandleAt,
            staleSeconds = staleSeconds,
            status = status,
            reason = reason,
            reasonDetail = detail,
            checkedAt = now,
        )
    }

    /** 배치 판정 — codes 순서 보존. */
    fun assessAll(
        codes: List<String>,
        interval: String = DEFAULT_INTERVAL,
    ): List<CandleHealth> = codes.map { assess(it, interval) }

    // ── 판정 원천 조회 (모두 읽기 전용 · fail-open) ──────────────────────────

    /**
     * lastCandleAt = max(DB last ts, Redis current `updatedAt`).
     * 장중 진행 봉은 DB 미확정이므로 Redis current `updatedAt`(마지막 tick 시각)이 더 최신일 수 있다.
     */
    private fun resolveLastCandleAt(
        code: String,
        interval: String,
    ): Instant? {
        val dbLast =
            try {
                candleJpaRepository.findMaxTsByCodeAndInterval(code, interval)
            } catch (e: Exception) {
                log.debug("candle-health DB last ts 조회 실패: code={} interval={} err={}", code, interval, e.message)
                null
            }
        val redisUpdatedAt = readCurrentCandleUpdatedAt(code, interval)
        return listOfNotNull(dbLast, redisUpdatedAt).maxOrNull()
    }

    /** Redis `candle:current:{code}:{interval}` hash `updatedAt`(마지막 tick 시각). */
    private fun readCurrentCandleUpdatedAt(
        code: String,
        interval: String,
    ): Instant? =
        try {
            val v = redisTemplate.opsForHash<String, String>().get(RedisKeyRegistry.Market.candleCurrent(code, interval), "updatedAt")
            v?.let { runCatching { Instant.parse(it) }.getOrNull() }
        } catch (e: Exception) {
            log.debug("candle-health current updatedAt 조회 실패: code={} err={}", code, e.message)
            null
        }

    private fun resolveSession(now: Instant): MarketSession =
        try {
            marketSessionResolver.resolve(KxInstant.fromEpochMilliseconds(now.toEpochMilli()))
        } catch (e: Exception) {
            log.debug("candle-health session 판정 실패 — UNKNOWN 처리: err={}", e.message)
            MarketSession.Closed(reason = null)
        }

    /**
     * reason 판정 — KDoc 우선순위 트리 정합.
     */
    private fun resolveReason(
        code: String,
        interval: String,
        status: CandleHealthStatus,
        session: MarketSession,
        staleSeconds: Long?,
        now: Instant,
    ): Pair<CandleHealthReason, String> {
        if (status == CandleHealthStatus.FRESH) {
            return CandleHealthReason.OK_REALTIME to "실시간 수집 정상 (마지막 봉 ${staleSeconds ?: 0}초 전)"
        }

        // 1. 장외/마감 — 최우선 (장외 stale는 정상)
        if (session !is MarketSession.Regular) {
            val sessionLabel = sessionLabel(session)
            return CandleHealthReason.MARKET_CLOSED to "$sessionLabel — 정규장 외 시간으로 실시간 수집이 중단된 정상 상태입니다"
        }

        // 2. 영구 회복 불가 갭 (KIS 보관 한도 밖)
        if (hasUnrecoverableGap(code, interval)) {
            return CandleHealthReason.BACKFILL_PERMANENT_GAP to
                "KIS 보관 한도(최대 1년) 밖 또는 연속 미반환으로 백필 재시도가 영구 중단된 갭입니다 (재수집 불가)"
        }

        // 3. 백필 재시도 중 실패 (miss counter 누적, 임계치 미달)
        backfillMissCount(code, interval)?.let { miss ->
            if (miss in 1 until BACKFILL_MISS_GIVE_UP_THRESHOLD) {
                return CandleHealthReason.BACKFILL_FAILED to
                    "KIS 백필이 연속 ${miss}회 전건 미반환 — 재시도 중입니다 (rate limit·일시 장애 가능)"
            }
        }

        // 4. 실시간 WS 미구독
        if (!isWsSubscribed(code)) {
            return CandleHealthReason.WS_NOT_SUBSCRIBED to
                "정규장 중이나 실시간(KIS WS) 구독이 없습니다 — watchlist 등록 또는 종목 상세 진입 시 구독됩니다"
        }

        // 5. 구독 중이나 tick 미도착
        if (status == CandleHealthStatus.STALE) {
            return CandleHealthReason.NO_TICK to
                "실시간 구독 중이나 최근 체결(tick)이 도착하지 않았습니다 (거래량 적거나 WS 지연 가능)"
        }

        // 6. NO_DATA + 정규장 + 구독 중 — 부트스트랩 진행 중일 수 있음
        return CandleHealthReason.UNKNOWN to "수집 정체 원인을 특정할 수 없습니다 (초기 수집 진행 중일 수 있음)"
    }

    private fun sessionLabel(session: MarketSession): String =
        when (session) {
            is MarketSession.Regular -> "정규장"
            is MarketSession.PreMarket -> "장전 시간외"
            is MarketSession.PostMarket -> "장후 시간외"
            is MarketSession.Closed -> session.reason ?: "장외시간"
        }

    private fun hasUnrecoverableGap(
        code: String,
        interval: String,
    ): Boolean =
        try {
            redisTemplate.hasKey(RedisKeyRegistry.Market.candleBackfillUnrecoverable(code, interval))
        } catch (e: Exception) {
            log.debug("candle-health unrecoverable 조회 실패 — false: code={} err={}", code, e.message)
            false
        }

    private fun backfillMissCount(
        code: String,
        interval: String,
    ): Long? =
        try {
            redisTemplate.opsForValue().get(RedisKeyRegistry.Market.candleBackfillMiss(code, interval))?.toLongOrNull()
        } catch (e: Exception) {
            log.debug("candle-health miss counter 조회 실패: code={} err={}", code, e.message)
            null
        }

    /**
     * 종목이 어떤 user의 `watchlist:source` 에라도 "live"(WS) 로 등록되었는지.
     * 또는 `quote:last:{code}` 의 source가 WS(=polling 아님) + 합리적 최신성이면 WS 구독으로 간주.
     *
     * watchlist:source 키 스캔은 user 수가 소규모(개인 시스템)이므로 SCAN 부하 무시 가능.
     */
    private fun isWsSubscribed(code: String): Boolean {
        // a. watchlist:source:{userId} hash 에 code=live 가 하나라도 있으면 구독으로 간주
        try {
            val keys = redisTemplate.keys(RedisKeyRegistry.Ops.watchlistSourcePattern())
            for (key in keys) {
                val source = redisTemplate.opsForHash<String, String>().get(key, code)
                if (source == "live") return true
            }
        } catch (e: Exception) {
            log.debug("candle-health watchlist:source 스캔 실패: code={} err={}", code, e.message)
        }
        // b. quote:last:{code} source 가 polling 이 아니면 WS tick 도착 이력으로 간주(fallback)
        return quoteSourceIsWs(code)
    }

    private fun quoteSourceIsWs(code: String): Boolean =
        try {
            val json = redisTemplate.opsForValue().get(RedisKeyRegistry.Market.quoteLast(code))
            // 단순 substring 판정 — ObjectMapper 의존 회피(읽기 fallback). polling 명시면 false.
            json != null && !json.contains("\"source\":\"polling\"")
        } catch (e: Exception) {
            log.debug("candle-health quote:last source 조회 실패: code={} err={}", code, e.message)
            false
        }
}
