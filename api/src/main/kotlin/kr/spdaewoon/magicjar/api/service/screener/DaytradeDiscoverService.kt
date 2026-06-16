package kr.spdaewoon.magicjar.api.service.screener

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverPresetDto
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverRequest
import kr.spdaewoon.magicjar.api.controller.screener.DaytradeDiscoverResultDto
import kr.spdaewoon.magicjar.api.controller.screener.ScreenerMatchDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.DaytradeDiscoverCache
import kr.spdaewoon.magicjar.domain.backtest.port.StrategyEvaluatorPort
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.screener.ScreenerFilter
import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch
import kr.spdaewoon.magicjar.domain.screener.ScreenerScoreBreakdown
import kr.spdaewoon.magicjar.domain.screener.SkipReason
import kr.spdaewoon.magicjar.domain.screener.port.InstrumentLookupPort
import kr.spdaewoon.magicjar.domain.screener.port.ScreenerRankerPort
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyException
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateDefinition
import kr.spdaewoon.magicjar.domain.strategyresearch.ResearchCandidateProvider
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 단타 발굴(Discovery) 서비스 (ADR 0117 §2-1·§2-3·§2-4 + spec §3-R2).
 *
 * "전략을 미리 작성하지 않아도 시스템 빌트인 단타 setup 룰로 오늘 후보 TOP N을 수치로 제시"하는 발굴 레이어.
 *
 * ### 진입 모델 (spec §1-1)
 * - 기존 `ScreenerService`는 `strategyId` 필수 — 사용자 저장 DSL 평가.
 * - 본 발굴은 `strategyId` 없이 **V75 시드 빌트인 프리셋**(`DAYTRADE_*`)의 `entry_dsl`로 universe를 평가한다.
 * - 프리셋 권위 = V75 Flyway 시드 row (최고 관리자 결정 2). 코드는 `ResearchCandidateProvider`로 read만.
 *
 * ### 재사용 (신규 엔진 작성 최소화 — task §2-2)
 * - universe 조회: `InstrumentLookupPort` (KIS 호출 0 — instrument 영속 reuse).
 * - candle: `CandleRepository.findLatestByCodeAndInterval` (영속 reuse — 일봉 read-only).
 * - DSL 평가: `StrategyEvaluatorPort.evaluate` (strategy-engine 평가기 — ScreenerEngineImpl과 동일 port).
 * - 점수/skip 매트릭스: ScreenerEngineImpl과 동일 normalize 산식(signal·volume·atr·cap).
 * - 랭킹: `ScreenerRankerPort` (MultiCriteriaRanker — fixed weights 그대로).
 *
 * ### owner 가드 — watchlist write 0 LOCK (CLAUDE.md · spec §1-2 · ADR 0117 §2-4)
 * 발굴 후보는 `startRealtimeWatch=true`일 때만 `market.realtime.demand.v1`에 owner=STRATEGY 수요로 발행한다.
 * 본 서비스는 watchlistRepository를 import하지 않으며, 어떤 경로로도 watchlist 테이블에 write하지 않는다.
 * (ScreenerService.publishStrategyRealtimeDemands 패턴 1:1 — owner=STRATEGY · priority 40 · TTL 30분.)
 *
 * ### AI 호출 0 LOCK (개발 룰 5 + ADR 0117 §2-3)
 * 본 서비스는 LLM provider import 0 — 룰 기반 발굴. `ai-discovery`(LLM)와 명확히 분리.
 *
 * ### mock 합성 0 (메모리 feedback_no_mock_data)
 * candle 0건/부족 시 skip 매트릭스에 정직하게 사유 표기 — 합성 데이터 0.
 */
@Service
class DaytradeDiscoverService(
    private val candidateProvider: ResearchCandidateProvider,
    private val parser: KisYamlDslParser,
    private val instrumentLookupProvider: ObjectProvider<InstrumentLookupPort>,
    private val candleRepositoryProvider: ObjectProvider<CandleRepository>,
    private val strategyEvaluatorPort: StrategyEvaluatorPort,
    private val ranker: ScreenerRankerPort,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val discoverCache: DaytradeDiscoverCache,
    @Value("\${magicjar.daytrade.discovery.enabled:true}") private val discoveryEnabled: Boolean,
    @Value("\${magicjar.screener.candle-lookback-days:90}") private val candleLookbackDays: Int,
    @Value("\${magicjar.screener.min-candles:20}") private val minCandles: Int,
) {
    private val log = LoggerFactory.getLogger(DaytradeDiscoverService::class.java)

    fun discover(request: DaytradeDiscoverRequest): DaytradeDiscoverResultDto {
        val started = System.currentTimeMillis()

        if (!discoveryEnabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "단타 발굴이 비활성화되어 있습니다 (MAGICJAR_DAYTRADE_DISCOVERY_ENABLED=false)")
        }

        val interval = parseInterval(request.interval)
        val filter = buildFilter(request)

        // ── Step 1: V75 시드 빌트인 단타 프리셋 read (코드는 read만 — 시드가 권위) ──────────
        val presets = resolvePresets(request.presetKey)
        if (presets.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "단타 발굴 프리셋을 찾을 수 없습니다 (presetKey=${request.presetKey ?: "<default DAYTRADE_*>"})",
            )
        }

        // 각 프리셋의 entry_dsl → AST 파싱 (파싱 실패 프리셋은 graceful skip — 발굴 안전 실패 원칙)
        val parsedPresets = presets.mapNotNull { preset -> parsePreset(preset) }
        if (parsedPresets.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "단타 발굴 프리셋의 entry_dsl을 평가할 수 없습니다 (모든 프리셋 파싱 실패)",
            )
        }

        // ── Step 1.5: 캐시 조회 (perf — userId 무관·거래일 동일이면 일봉 결과 동일) ──────────────
        // 빌트인 단타 프리셋은 userId 무관 + 같은 거래일이면 일봉 기반 결과 동일 → (preset·filter·interval·limit·거래일) 키.
        // HIT 시 무거운 universe 평가(약 28s)를 생략하고, realtime watch 발행(side-effect)만 매 호출 재수행.
        val cacheKey =
            discoverCache.buildKey(
                presetKey = request.presetKey,
                filterFingerprint = filterFingerprint(filter),
                interval = request.interval,
                limit = request.limit,
            )
        val cached = discoverCache.get(cacheKey)
        if (cached != null) {
            val watchRegistered =
                if (request.startRealtimeWatch) {
                    publishStrategyRealtimeDemands(cached.matches.map { it.toDomainMatch() }, request, interval)
                } else {
                    0
                }
            val elapsedHit = System.currentTimeMillis() - started
            log.info(
                "단타 발굴 캐시 HIT: userId={} key={} matched={} watchRegistered={} elapsedMs={}",
                request.userId,
                cacheKey,
                cached.matchedCount,
                watchRegistered,
                elapsedHit,
            )
            return cached.copy(elapsedMs = elapsedHit, realtimeWatchRegistered = watchRegistered)
        }

        // ── Step 2: 의존 graceful fallback (Phase 미합류 환경) ──────────────────────────────
        val instrumentLookup = instrumentLookupProvider.ifAvailable
        val candleRepository = candleRepositoryProvider.ifAvailable
        if (instrumentLookup == null || candleRepository == null) {
            log.debug("InstrumentLookupPort/CandleRepository bean 부재 — empty 발굴 결과 fallback")
            return emptyResult(parsedPresets, elapsed = System.currentTimeMillis() - started)
        }

        // ── Step 3: universe 조회 (KIS 호출 0 — 영속 reuse) ───────────────────────────────────
        val universe = instrumentLookup.findActive(filter)
        if (universe.isEmpty()) {
            log.info("단타 발굴 universe empty: markets={} types={}", filter.markets.map { it.name }, filter.types.map { it.name })
            return emptyResult(parsedPresets, elapsed = System.currentTimeMillis() - started)
        }

        // ── Step 4: universe iterate — 프리셋 OR 매칭(어느 한 프리셋이라도 BUY면 후보) ──────────
        val skipCounts: MutableMap<String, Int> = LinkedHashMap()
        val rawMatches: MutableList<ScreenerMatch> = ArrayList()
        var evaluatedCount = 0
        val candleFetchLimit = maxOf(candleLookbackDays, minCandles).coerceAtLeast(1)

        for (instrument in universe) {
            val candles = candleRepository.findLatestByCodeAndInterval(instrument.code, interval, candleFetchLimit)
            if (candles.isEmpty()) {
                incrementSkip(skipCounts, SkipReason.CandleMissing.label)
                continue
            }
            if (candles.size < minCandles) {
                incrementSkip(skipCounts, SkipReason.CandleInsufficient(need = minCandles, have = candles.size).label)
                continue
            }
            val slice = candles.takeLast(candleLookbackDays)
            evaluatedCount++

            // 어느 프리셋이든 첫 BUY 매칭이면 후보로 채택 (matched preset의 reason 사용)
            val matched = evaluateAgainstPresets(parsedPresets, slice, skipCounts)
            if (matched != null) {
                rawMatches.add(buildMatch(instrument, slice, matched.strength, matched.reasonString, matched.presetKey))
            }
        }

        // ── Step 5: 랭킹 + limit ────────────────────────────────────────────────────────────
        val ranked = ranker.rank(rawMatches, request.limit)
        val skippedCount = skipCounts.values.sum()
        val computeElapsed = System.currentTimeMillis() - started

        // ── Step 5.5: 발굴 계산 결과 캐시 저장 (realtimeWatchRegistered 제외 — side-effect는 매 호출) ──
        val computedResult =
            DaytradeDiscoverResultDto(
                presets = parsedPresets.map { it.toMetaDto() },
                isBuiltin = parsedPresets.all { it.isBuiltin },
                universeSize = universe.size,
                evaluatedCount = evaluatedCount,
                matchedCount = ranked.size,
                skippedReasons = skipCounts.toMap(),
                matches = ranked.map { it.toMatchDto() },
                elapsedMs = computeElapsed,
                realtimeWatchRegistered = 0,
            )
        discoverCache.put(cacheKey, computedResult)

        // ── Step 6: 장중 감시 — STRATEGY owner WS 수요 발행 (watchlist write 0) ────────────────
        val watchRegistered =
            if (request.startRealtimeWatch) {
                publishStrategyRealtimeDemands(ranked, request, interval)
            } else {
                0
            }

        log.info(
            "단타 발굴 완료(캐시 MISS·계산): userId={} presets={} universeSize={} evaluated={} matched={} skipped={} watchRegistered={} elapsedMs={}",
            request.userId,
            parsedPresets.map { it.key },
            universe.size,
            evaluatedCount,
            ranked.size,
            skippedCount,
            watchRegistered,
            computeElapsed,
        )

        return computedResult.copy(realtimeWatchRegistered = watchRegistered)
    }

    // ── 프리셋 resolve / parse ─────────────────────────────────────────────────────────────

    private fun resolvePresets(presetKey: String?): List<ResearchCandidateDefinition> {
        val all = candidateProvider.candidates()
        return if (presetKey != null) {
            all.filter { it.key == presetKey }
        } else {
            // default = 활성 DAYTRADE_* 빌트인 프리셋 전체 합집합
            all.filter { it.key.startsWith(DAYTRADE_PREFIX) }
        }
    }

    private fun parsePreset(preset: ResearchCandidateDefinition): ParsedPreset? =
        runCatching {
            val ast = parser.parse(preset.entryDsl)
            parser.validateStatic(ast)
            ParsedPreset(
                key = preset.key,
                title = preset.title,
                entryRules = preset.entryRules,
                isBuiltin = preset.isBuiltin,
                ast = ast,
            )
        }.onFailure {
            log.warn("단타 발굴 프리셋 entry_dsl 파싱/검증 실패 — 해당 프리셋 skip: key={} dsl='{}' err={}", preset.key, preset.entryDsl, it.message)
        }.getOrNull()

    /**
     * slice를 모든 프리셋에 평가 — 첫 BUY 매칭 프리셋의 결과 반환 (없으면 null).
     * 평가 timeout/오류는 skip 매트릭스에 누적하고 다음 프리셋으로 진행 (전체 발굴을 깨지 않음).
     */
    private fun evaluateAgainstPresets(
        parsedPresets: List<ParsedPreset>,
        slice: List<Candle>,
        skipCounts: MutableMap<String, Int>,
    ): MatchedPreset? {
        for (preset in parsedPresets) {
            val evalResult =
                try {
                    // strategyId = -1L: 발굴 sentinel (저장 전략 아님 — auto-disable 미적용)
                    strategyEvaluatorPort.evaluate(DISCOVERY_STRATEGY_ID, preset.ast, slice)
                } catch (e: StrategyException.StrategyTimeoutException) {
                    incrementSkip(skipCounts, SkipReason.EvalTimeout.label)
                    continue
                } catch (e: StrategyException) {
                    incrementSkip(skipCounts, SkipReason.EvalError(e.message ?: e.javaClass.simpleName).label)
                    continue
                } catch (e: Exception) {
                    incrementSkip(skipCounts, SkipReason.EvalError(e.message ?: e.javaClass.simpleName).label)
                    continue
                }
            if (evalResult.signal == Signal.BUY) {
                val reasonString = "[${preset.key}] ${preset.title} — ${evalResult.reason}"
                return MatchedPreset(presetKey = preset.key, strength = evalResult.strength, reasonString = reasonString)
            }
        }
        return null
    }

    // ── 매칭 score 빌드 (ScreenerEngineImpl normalize 산식 1:1 재사용) ──────────────────────

    private fun buildMatch(
        instrument: Instrument,
        slice: List<Candle>,
        strength: BigDecimal,
        reason: String,
        @Suppress("UNUSED_PARAMETER") presetKey: String,
    ): ScreenerMatch {
        val last = slice.last()
        val close = (last.closeAdj ?: last.closeRaw).max(BigDecimal.ONE)
        val volume = BigDecimal.valueOf(last.volume.coerceAtLeast(0L))

        val signalScore = strength.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        val turnover = close.multiply(volume)
        val volumeScore = normalizeLog(turnover, MIN_TURNOVER, MAX_TURNOVER)
        // ATR 점수: 단일 캔들 (high-low)/close 가 아닌 14봉 smoothed NATR(평균 TrueRange / close).
        // 단일 캔들 proxy는 노이즈로 단타 movers(마지막 봉 변동 큰 종목)를 ATR_MAX 위로 clamp → 0.0 다발 발생했음
        // (실측 089030 마지막 봉 0.224 > 0.20 → 0.0이나, 14봉 NATR은 0.099로 정상). spec §3-R2 normalize 재보정.
        val natr = smoothedNatr(slice, close)
        val atrScore = atrSweetSpot(natr)
        val capScore = BigDecimal.ONE

        val breakdown =
            ScreenerScoreBreakdown(
                signal = signalScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                volume = volumeScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                atr = atrScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                cap = capScore.setScale(SCALE, RoundingMode.HALF_EVEN),
                total = BigDecimal.ZERO,
            )
        return ScreenerMatch(
            instrumentCode = instrument.code,
            instrumentName = instrument.name,
            rank = 0,
            score = BigDecimal.ZERO,
            breakdown = breakdown,
            reason = reason,
        )
    }

    private fun normalizeLog(
        value: BigDecimal,
        min: BigDecimal,
        max: BigDecimal,
    ): BigDecimal {
        val safeValue = value.max(min)
        val ratio =
            (Math.log10(safeValue.toDouble()) - Math.log10(min.toDouble())) /
                (Math.log10(max.toDouble()) - Math.log10(min.toDouble()))
        return BigDecimal.valueOf(ratio.coerceIn(0.0, 1.0))
    }

    /**
     * 14봉 smoothed NATR(Normalized ATR) = mean(TrueRange over last [ATR_PERIOD] bars) / lastClose.
     *
     * TrueRange = max(high-low, |high-prevClose|, |low-prevClose|) — Wilder 정의의 simple-mean 근사.
     * (단타 발굴은 read-only 일봉 normalize용 proxy — indicator-impl의 EMA-smoothed ATR과 동일 의도이나,
     *  api-local 산식으로 모듈 경계 add-only 유지 + IndicatorVolatility는 internal object라 직접 호출 불가.)
     *
     * candle 부족(< 2)·close 0 시 0 반환 → atrSweetSpot floor 적용.
     */
    private fun smoothedNatr(
        slice: List<Candle>,
        lastClose: BigDecimal,
    ): BigDecimal {
        if (slice.size < 2 || lastClose.signum() <= 0) return BigDecimal.ZERO
        val window = slice.takeLast(ATR_PERIOD + 1)
        var trSum = 0.0
        var trCount = 0
        for (i in 1 until window.size) {
            val cur = window[i]
            val prev = window[i - 1]
            val high = (cur.highAdj ?: cur.highRaw).toDouble()
            val low = (cur.lowAdj ?: cur.lowRaw).toDouble()
            val prevClose = (prev.closeAdj ?: prev.closeRaw).toDouble()
            val tr = maxOf(high - low, kotlin.math.abs(high - prevClose), kotlin.math.abs(low - prevClose))
            trSum += tr
            trCount++
        }
        if (trCount == 0) return BigDecimal.ZERO
        val natr = (trSum / trCount) / lastClose.toDouble()
        return BigDecimal.valueOf(natr.coerceAtLeast(0.0))
    }

    /**
     * 단타 적합 NATR sweet-spot — 단타는 일중 변동성이 클수록(scalp 여지) 유리.
     *
     * 14봉 smoothed NATR 실측 분포(universe 일봉): p10≈4.5% · p50≈8% · p90≈14% · p99≈22%.
     * 단일-캔들 proxy 시절의 [0.005, 0.20] peak 0.04 밴드는 smoothed NATR에 과대축소 → 중앙값 종목도 하강.
     * 단타 활성 zone을 plateau로 보상하고 극단(거래정지 risk)에서만 taper:
     * - NATR < [NATR_FLOOR] (1%)        → 0 (변동성 부족, 단타 부적합)
     * - [NATR_FLOOR] ~ [NATR_RAMP] (4%) → 0 → 1 ramp up
     * - [NATR_RAMP] ~ [NATR_PLATEAU] (25%) → 1.0 plateau (단타 적합 zone, p10~p99 포괄)
     * - [NATR_PLATEAU] ~ [NATR_CEIL] (50%) → 1 → 0 taper down (과변동·정지 risk)
     * - > [NATR_CEIL]                    → 0
     */
    private fun atrSweetSpot(natr: BigDecimal): BigDecimal {
        val r = natr.toDouble()
        val score =
            when {
                r < NATR_FLOOR -> 0.0
                r < NATR_RAMP -> (r - NATR_FLOOR) / (NATR_RAMP - NATR_FLOOR)
                r <= NATR_PLATEAU -> 1.0
                r < NATR_CEIL -> (NATR_CEIL - r) / (NATR_CEIL - NATR_PLATEAU)
                else -> 0.0
            }
        return BigDecimal.valueOf(score.coerceIn(0.0, 1.0))
    }

    // ── owner 가드 — STRATEGY owner WS 수요 발행 (watchlist write 0) ────────────────────────

    /**
     * 발굴 후보를 `market.realtime.demand.v1`에 owner=STRATEGY 수요로 발행한다.
     *
     * ★ watchlist write 0 LOCK: 본 메서드는 watchlist 테이블/Repository에 write하지 않는다.
     *   ownerId = "daytrade-discover:<presetKey>" — 발굴 세션 식별자(단순 합집합 방지).
     *   priority 40 (watchlist/HOLDING 뒤 — 보유 구독 보호), TTL 30분 (STRATEGY owner default).
     *   ScreenerService.publishStrategyRealtimeDemands 패턴 1:1.
     *
     * @return 발행 성공 종목 수
     */
    private fun publishStrategyRealtimeDemands(
        matches: List<ScreenerMatch>,
        request: DaytradeDiscoverRequest,
        interval: CandleInterval,
    ): Int {
        val topMatches = matches.take(request.limit.coerceIn(1, STRATEGY_REALTIME_DEMAND_MAX))
        if (topMatches.isEmpty()) return 0
        val ownerId = "daytrade-discover:${request.presetKey ?: "default"}"

        var registered = 0
        topMatches.forEachIndexed { index, match ->
            runCatching {
                val payload =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "action" to "ENTER",
                            "owner" to "STRATEGY",
                            "ownerId" to ownerId,
                            "code" to match.instrumentCode,
                            "priority" to (STRATEGY_REALTIME_PRIORITY_BASE + index),
                            "ttlSeconds" to STRATEGY_REALTIME_TTL_SECONDS,
                            "rank" to match.rank,
                            "interval" to interval.code,
                            "source" to "DAYTRADE_DISCOVER",
                            "ts" to Instant.now().toString(),
                        ),
                    )
                kafkaTemplate.send(KafkaTopics.Market.REALTIME_DEMAND, match.instrumentCode, payload)
                registered++
            }.onFailure { ex ->
                log.warn("단타 발굴 realtime demand 발행 실패 (무시): ownerId={} code={} err={}", ownerId, match.instrumentCode, ex.message)
            }
        }
        log.info(
            "단타 발굴 realtime demand 발행 (owner=STRATEGY, watchlist write 0): ownerId={} interval={} count={}",
            ownerId,
            interval.code,
            registered,
        )
        return registered
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private fun parseInterval(interval: String): CandleInterval =
        runCatching { CandleInterval.fromCode(interval) }.getOrElse {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 interval: $interval (허용: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            )
        }

    private fun buildFilter(request: DaytradeDiscoverRequest): ScreenerFilter {
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
        return ScreenerFilter(markets = markets, types = types, marketCapMin = dto.marketCapMin, avgVolumeMin = dto.avgVolumeMin)
    }

    /** universe 필터를 캐시 키용 안정 문자열로 직렬화 (markets·types 정렬 → 순서 무관 동일 키). */
    private fun filterFingerprint(filter: ScreenerFilter): String =
        buildString {
            append(
                filter.markets
                    .map { it.name }
                    .sorted()
                    .joinToString(","),
            )
            append("|")
            append(
                filter.types
                    .map { it.name }
                    .sorted()
                    .joinToString(","),
            )
            append("|cap=").append(filter.marketCapMin ?: "")
            append("|vol=").append(filter.avgVolumeMin ?: "")
        }

    private fun incrementSkip(
        counts: MutableMap<String, Int>,
        label: String,
    ) {
        counts.merge(label, 1) { a, b -> a + b }
    }

    private fun emptyResult(
        parsedPresets: List<ParsedPreset>,
        elapsed: Long,
    ): DaytradeDiscoverResultDto =
        DaytradeDiscoverResultDto(
            presets = parsedPresets.map { it.toMetaDto() },
            isBuiltin = parsedPresets.all { it.isBuiltin },
            universeSize = 0,
            evaluatedCount = 0,
            matchedCount = 0,
            skippedReasons = emptyMap(),
            matches = emptyList(),
            elapsedMs = elapsed,
            realtimeWatchRegistered = 0,
        )

    private fun ScreenerMatch.toMatchDto(): ScreenerMatchDto =
        ScreenerMatchDto(
            rank = rank,
            instrumentCode = instrumentCode,
            instrumentName = instrumentName,
            score = score,
            scoreComponents = breakdown.toComponentMap(),
            reason = reason,
        )

    /**
     * 캐시된 DTO match → 도메인 ScreenerMatch (realtime watch 발행용).
     * publishStrategyRealtimeDemands는 instrumentCode·rank만 사용하므로 breakdown은 0 placeholder로 복원한다.
     */
    private fun ScreenerMatchDto.toDomainMatch(): ScreenerMatch =
        ScreenerMatch(
            instrumentCode = instrumentCode,
            instrumentName = instrumentName,
            rank = rank,
            score = score,
            breakdown =
                ScreenerScoreBreakdown(
                    signal = scoreComponents["signal"] ?: BigDecimal.ZERO,
                    volume = scoreComponents["volume"] ?: BigDecimal.ZERO,
                    atr = scoreComponents["atr"] ?: BigDecimal.ZERO,
                    cap = scoreComponents["cap"] ?: BigDecimal.ZERO,
                    total = score,
                ),
            reason = reason,
        )

    private data class ParsedPreset(
        val key: String,
        val title: String,
        val entryRules: List<String>,
        val isBuiltin: Boolean,
        val ast: DslNode,
    ) {
        fun toMetaDto(): DaytradeDiscoverPresetDto = DaytradeDiscoverPresetDto(presetKey = key, title = title, entryRules = entryRules)
    }

    private data class MatchedPreset(
        val presetKey: String,
        val strength: BigDecimal,
        val reasonString: String,
    )

    private companion object {
        const val DAYTRADE_PREFIX = "DAYTRADE_"
        const val DISCOVERY_STRATEGY_ID = -1L
        const val SCALE: Int = 6
        val MIN_TURNOVER: BigDecimal = BigDecimal("1000000")
        val MAX_TURNOVER: BigDecimal = BigDecimal("1000000000000")

        /** smoothed NATR 윈도우 (TrueRange 평균 봉수). */
        const val ATR_PERIOD: Int = 14

        /** 단타 NATR sweet-spot 밴드 (smoothed NATR 실측 분포 기반 재보정). */
        const val NATR_FLOOR: Double = 0.01
        const val NATR_RAMP: Double = 0.04
        const val NATR_PLATEAU: Double = 0.25
        const val NATR_CEIL: Double = 0.50
        const val STRATEGY_REALTIME_DEMAND_MAX = 30
        const val STRATEGY_REALTIME_PRIORITY_BASE = 40
        const val STRATEGY_REALTIME_TTL_SECONDS = 30L * 60L
    }
}
