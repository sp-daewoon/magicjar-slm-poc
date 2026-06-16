package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerMarketDailyUniverseJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MarketDailyBackfillCampaign — ADR 0118 R-BF 10년 전종목 일봉 멱등 backfill 캠페인.
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MarketDailyUniverseProvider.resolveCodes] 보통주 ~2,517 순회 (R1 재사용).
 *   2. 종목별 **candle-row 기반 progress** 산출 — 이미 적재 완료(row≥cap AND 최근까지) 종목은 skip(멱등).
 *   3. **listed_at clamp** — 시작 경계 = max(today-historyYears, instrument.listed_at). 상장 이전 fetch 0.
 *   4. 종목당 [KisDailyCandleRangePort.fetchDailyCandlesInRange] 1회 (BACKFILL 버킷) → sanity → saveAll(DO NOTHING).
 *   5. **밤당 콜 예산 cap** — [MarketDailyBackfillProperties.nightlyCallCap] 도달 시 즉시 종료(다음 실행 이연).
 *   6. throttle gate(직렬화·run 간격·circuit breaker) + audit 재사용.
 *
 * ## 재사용 자산 (신규 로직 최소)
 *   - sanity check: [CandleBackfillWorker.sanityCheck] (햇징 룰 §1 — 가격 range·OHLC·monotonic).
 *   - throttle·circuit breaker: [BackfillThrottleGate].
 *   - universe·listed_at: [MarketDailyUniverseProvider] + [ConsumerMarketDailyUniverseJpaRepository.findListedAtByCode] (R1).
 *   - 적재: [CandleRepository.saveAll] (ON CONFLICT DO NOTHING — 과거봉 immutable).
 *   - progress 산출: [CandleRepository.countByCodeAndInterval] + [CandleRepository.findMaxTsByCodeAndInterval].
 *
 * ## ENV off 중단 / 시동
 *   [MarketDailyBackfillProperties.enabled] off → [runCampaign]이 즉시 DISABLED 종료(적재분 보존).
 *   admin endpoint 또는 야간 스케줄러가 매 호출마다 progress 이어받아 미완료만 처리(멱등 재개).
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only).
 */
@Component
class MarketDailyBackfillCampaign(
    private val universeProvider: MarketDailyUniverseProvider,
    private val universeRepository: ConsumerMarketDailyUniverseJpaRepository,
    private val kisDailyRangePort: KisDailyCandleRangePort,
    private val candleRepository: CandleRepository,
    private val candleBackfillWorker: CandleBackfillWorker,
    private val backfillThrottleGate: BackfillThrottleGate,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
    private val properties: MarketDailyBackfillProperties,
    private val wipeService: MarketDailyWipeService,
) {
    private val log = LoggerFactory.getLogger(MarketDailyBackfillCampaign::class.java)

    companion object {
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val INTERVAL = CandleInterval.ONE_DAY

        /**
         * candle.source DB 컬럼 한계 — `VARCHAR(20)`
         * (api/src/main/resources/db/migration/V1__baseline.sql:115).
         * 이 캠페인 source 마킹이 이 한계를 넘으면 PostgreSQL 22001 (value too long) 으로
         * saveAll 전건 실패 → 0행 적재. 회귀 방지로 [BACKFILL_SOURCE] 길이를 init 에서 단언.
         */
        const val SOURCE_MAX_LEN = 20

        /**
         * 이 캠페인이 적재하는 일봉의 source 마킹. 기존 candle source 컨벤션 정합
         * — `"KIS-BACKFILL"`(14자) · `"KIS-RANGE-GAPFILL"`(19자) 와 충돌 없는 별도 값.
         * `"KIS-MKT-DAILY-BF"` = 16자 ≤ [SOURCE_MAX_LEN] (varchar(20) 안전).
         * (직전 `"KIS-MARKET-DAILY-BACKFILL"` = 25자 → varchar(20) overflow 결함, hotfix).
         */
        const val BACKFILL_SOURCE = "KIS-MKT-DAILY-BF"

        /**
         * 신규/단기 상장 종목 완료 판정 — clamp 구간 기대 거래일 대비 보유 비율 임계.
         * 0.90 = 주말 제외 평일 수(공휴일 포함 과대 추정) 대비 90% 이상 보유면 "전수 적재"로 본다.
         * (공휴일·거래정지일로 실제 거래일 < 평일 수 이므로 100% 요구는 영구 미완료 risk → 0.90 보수).
         */
        const val COVERAGE_RATIO = 0.90

        /**
         * audit_trail.entity_id VARCHAR(40) 한계 (api/V1__baseline.sql:215).
         * runId(`mkt-daily-bf:<epoch>-<uuid>` ~60자)를 그대로 entity_id 에 넣으면 22001 overflow →
         * audit 미기록("value too long for varying(40)" 로그). 캠페인 run audit 의 entity_id 는
         * 짧은 식별자([CAMPAIGN_ENTITY_ID])로 고정하고, 전체 runId 는 detail_json 에 보존한다.
         */
        const val AUDIT_ENTITY_ID_MAX_LEN = 40
        const val CAMPAIGN_ENTITY_ID = "mkt-daily-bf"

        /** no-advance cooldown audit action — 종목별 entity_id=code(6자, ≤40 안전)로 마킹. */
        const val ACTION_NO_ADVANCE = "MARKET_DAILY_BACKFILL_NO_ADVANCE"
    }

    init {
        // 회귀 가드 — source 마킹이 candle.source VARCHAR(20) 를 넘으면 부팅 시점 즉시 실패.
        // (런타임 saveAll 전건 실패로 0행 적재되는 silent 결함을 컴포넌트 생성 단계로 끌어올림)
        require(BACKFILL_SOURCE.length <= SOURCE_MAX_LEN) {
            "BACKFILL_SOURCE '$BACKFILL_SOURCE' length=${BACKFILL_SOURCE.length} " +
                "exceeds candle.source VARCHAR($SOURCE_MAX_LEN)"
        }
    }

    enum class CampaignStatus {
        DISABLED, // ENV enabled=false — 가동 0 (적재분 보존)
        COMPLETED, // 미완료 종목 0 — 전종목 10년 적재 완료
        CAP_REACHED, // 밤당 콜 예산 소진 — 미완료분 다음 실행 이연
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
    }

    /**
     * 캠페인 실행 모드 — ADR 0118 amend.
     *
     * - [NORMAL]: 기존 멱등 재개(default). **절대 wipe 하지 않음** — 이미 적재된 일봉 보존.
     * - [MIGRATE]: 일봉 패밀리(1d·1wk·1mo·1y) wipe 후 J 채널로 전체 재적재. UN/NX stale 동결 일소 1회용.
     *   wipe 는 [MarketDailyWipeService] 화이트리스트 LOCK 으로 1m·5m 절대 보존.
     */
    enum class BackfillMode {
        NORMAL,
        MIGRATE,
    }

    data class CampaignResult(
        val runId: String,
        val status: CampaignStatus,
        val mode: BackfillMode,
        val wipedRows: Long,
        val universeSize: Int,
        val alreadyComplete: Int,
        val processedSymbols: Int,
        val newlyCompleteSymbols: Int,
        val insertedRows: Long,
        val callsConsumed: Int,
        val callCap: Int,
        val failedSymbols: Int,
        val skippedThrottle: Int,
        val coolingDownSymbols: Int,
        val remainingSymbols: Int,
        val durationMs: Long,
    )

    /**
     * 멱등 캠페인 1회 실행 — 매 호출마다 progress 이어받아 미완료 종목만 [callCap]까지 처리.
     *
     * @param mode [BackfillMode.NORMAL](default)=wipe 0·멱등 재개. [BackfillMode.MIGRATE]=일봉 패밀리 wipe 후
     *   J 채널 전체 재적재(UN/NX stale 동결 일소 1회용). MIGRATE 도 [MarketDailyBackfillProperties.enabled]
     *   게이트를 그대로 따른다(off 면 wipe 0).
     * @return 실행 요약 (status·mode·wipedRows·콜 소비·잔여 종목 등 — 운영 evidence).
     */
    fun runCampaign(mode: BackfillMode = BackfillMode.NORMAL): CampaignResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-bf:$startMs-${UUID.randomUUID()}"

        if (!properties.enabled) {
            log.info("MarketDailyBackfillCampaign DISABLED — ENV magicjar.market-daily-backfill.enabled=false (가동 0)")
            return emptyResult(runId, CampaignStatus.DISABLED, mode, 0L, startMs)
        }

        // ★ MIGRATE 모드 — 일봉 패밀리(1d·1wk·1mo·1y) wipe 선행. 1m·5m 는 화이트리스트 LOCK 으로 절대 보존.
        // wipe 는 멱등(반환 0까지 배치 삭제) — 부분 완료 후 재호출 시 잔여만 삭제. NORMAL 은 wipe 0(보존).
        var wipedRows = 0L
        if (mode == BackfillMode.MIGRATE) {
            log.warn(
                "MarketDailyBackfillCampaign MIGRATE — 일봉 패밀리 wipe 선행(1m·5m 보존): runId={} intervals={}",
                runId,
                MarketDailyWipeService.MIGRATE_WIPE_INTERVALS,
            )
            wipedRows = wipeService.wipeDailyFamily()
            audit(
                "MARKET_DAILY_BACKFILL_WIPE",
                campaignAuditEntityId(runId),
                mapOf(
                    "runId" to runId,
                    "mode" to mode.name,
                    "wipedRows" to wipedRows,
                    "intervals" to MarketDailyWipeService.MIGRATE_WIPE_INTERVALS,
                ),
            )
            log.warn("MarketDailyBackfillCampaign MIGRATE wipe 완료 — wipedRows={} runId={}", wipedRows, runId)
        }

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyBackfillCampaign EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_BACKFILL_EMPTY", campaignAuditEntityId(runId), mapOf("runId" to runId))
            return emptyResult(runId, CampaignStatus.EMPTY_UNIVERSE, mode, wipedRows, startMs)
        }

        val callCap = properties.normalizedNightlyCallCap()
        val today = LocalDate.now(KST_ZONE)
        // today 봉은 snapshot(R2) 소관 — backfill 종료 경계는 어제까지.
        val toDate = today.minusDays(1)
        val historyFloor = today.minusYears(properties.normalizedHistoryYears().toLong())

        log.info(
            "MarketDailyBackfillCampaign 시동: runId={} universe={}종목 callCap={} historyYears={} 경계={}..{}",
            runId,
            codes.size,
            callCap,
            properties.normalizedHistoryYears(),
            historyFloor,
            toDate,
        )
        audit(
            "MARKET_DAILY_BACKFILL_START",
            campaignAuditEntityId(runId),
            mapOf(
                "runId" to runId,
                "universeSize" to codes.size,
                "callCap" to callCap,
                "historyYears" to properties.normalizedHistoryYears(),
                "from" to historyFloor.toString(),
                "to" to toDate.toString(),
            ),
        )

        // ── 원자적 회계 (동시성 보호 · ADR 0118 amend) ──────────────────────────────────
        // call-cap·progress·no-advance·audit 회계가 coroutine 동시 종목 처리에서 깨지지 않도록 Atomic.
        val alreadyComplete = AtomicInteger(0)
        val processed = AtomicInteger(0)
        val newlyComplete = AtomicInteger(0)
        val insertedRows = AtomicLong(0L)
        val callsConsumed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val skippedThrottle = AtomicInteger(0)
        val coolingDown = AtomicInteger(0)
        // throttle/circuit OPEN 또는 콜 cap 도달 시 신규 launch 중단(cooperative). 이미 떠있는 종목은 완주.
        val stopLaunching = AtomicBoolean(false)

        val concurrency = properties.normalizedConcurrency()
        val launchDelayMs = properties.normalizedLaunchDelayMs()
        log.info(
            "MarketDailyBackfillCampaign 동시성: concurrency={} launchDelayMs={} (rate-limiter hard ceiling 별도 유효)",
            concurrency,
            launchDelayMs,
        )

        // coroutine Semaphore = throughput shaping(동시 종목 수 제한). KIS hard ceiling 은
        // ConsumerRedisKisRateLimiterAdapter(Bucket4j Redis bucket)가 어댑터 내부에서 그대로 유효 — 세마포어가
        // rate limiter 를 우회·중복하지 않음. blocking WebClient .block() 은 Dispatchers.IO 에서 격리 실행.
        runBlocking {
            val gate = Semaphore(concurrency)
            val dispatcher = Dispatchers.IO.limitedParallelism(concurrency)
            val jobs = mutableListOf<Job>()
            for (code in codes) {
                if (stopLaunching.get()) break
                // 콜 예산 cap — 이미 cap 도달이면 신규 launch 중단(이연). 보수적으로 cap 도달 시 중단.
                if (callsConsumed.get() >= callCap) {
                    stopLaunching.set(true)
                    break
                }
                if (launchDelayMs > 0L) delay(launchDelayMs) // 종목 launch 간 지연 — burst 분산
                jobs +=
                    launch(dispatcher) {
                        gate.withPermit {
                            // stop 신호(throttle/circuit OPEN·cap 도달) 이후 큐잉된 종목은 처리 0(다음 실행 이연).
                            // permit fair-FIFO 라 앞선 종목이 stop 을 set 한 뒤 본 permit 이 진입 → 일관 skip.
                            if (stopLaunching.get()) {
                                return@withPermit
                            }
                            // 멱등 — 이미 완료된 종목 skip (candle-row 기반 progress, gap-aware).
                            if (isSymbolComplete(code, historyFloor, toDate)) {
                                alreadyComplete.incrementAndGet()
                                return@withPermit
                            }
                            // ★ KIS-unavailable 무한 재fetch 가드 (over-fetch 닫기) — 직전 run 들이 fetch 했으나
                            //   max_ts 가 전진하지 못한(KIS 신규 데이터 0) 종목은 cooldown 동안 skip. cooldown 만료
                            //   후 재진입(corporate action 해소 등 데이터 출현 대비). 완료와 분리 집계.
                            if (isInNoAdvanceCooldown(code, today)) {
                                coolingDown.incrementAndGet()
                                return@withPermit
                            }
                            // cap 재확인 — launch 시점 이후 다른 종목이 cap 을 채웠을 수 있음(원자적).
                            if (callsConsumed.get() >= callCap) {
                                stopLaunching.set(true)
                                return@withPermit
                            }

                            when (val outcome = processSymbol(code, historyFloor, toDate, runId, today)) {
                                is SymbolOutcome.Throttled -> {
                                    skippedThrottle.incrementAndGet()
                                    // throttle/circuit OPEN — 신규 launch 중단(다음 실행 이연·잔여>0 → CAP_REACHED).
                                    stopLaunching.set(true)
                                    log.warn(
                                        "MarketDailyBackfillCampaign throttle/circuit — 신규 launch 중단(이월): code={} runId={}",
                                        code,
                                        runId,
                                    )
                                }
                                is SymbolOutcome.Done -> {
                                    processed.incrementAndGet()
                                    callsConsumed.addAndGet(outcome.callsUsed)
                                    insertedRows.addAndGet(outcome.inserted.toLong())
                                    if (outcome.complete) newlyComplete.incrementAndGet()
                                }
                                is SymbolOutcome.Failed -> {
                                    processed.incrementAndGet()
                                    callsConsumed.addAndGet(outcome.callsUsed)
                                    failed.incrementAndGet()
                                }
                            }
                        }
                    }
            }
            jobs.joinAll()
        }

        // 원자 카운터 → 최종 스냅샷(불변 로컬). 이후 result·log·audit 는 스냅샷만 사용(읽기 일관성).
        val alreadyCompleteFinal = alreadyComplete.get()
        val processedFinal = processed.get()
        val newlyCompleteFinal = newlyComplete.get()
        val insertedRowsFinal = insertedRows.get()
        val callsConsumedFinal = callsConsumed.get()
        val failedFinal = failed.get()
        val skippedThrottleFinal = skippedThrottle.get()
        val coolingDownFinal = coolingDown.get()

        val remaining = countRemaining(codes, historyFloor, toDate)
        // 잔여 0 = 전종목 완주(COMPLETED). 잔여>0 = 콜 cap·throttle 이월·cooldown 이연·루프 break 로
        // 미완료분 다음 실행 이연(CAP_REACHED). cooldown 종목은 미완료지만 KIS 가 새 데이터 줄 때까지 의도적 이연.
        val status = if (remaining == 0) CampaignStatus.COMPLETED else CampaignStatus.CAP_REACHED

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            CampaignResult(
                runId = runId,
                status = status,
                mode = mode,
                wipedRows = wipedRows,
                universeSize = codes.size,
                alreadyComplete = alreadyCompleteFinal,
                processedSymbols = processedFinal,
                newlyCompleteSymbols = newlyCompleteFinal,
                insertedRows = insertedRowsFinal,
                callsConsumed = callsConsumedFinal,
                callCap = callCap,
                failedSymbols = failedFinal,
                skippedThrottle = skippedThrottleFinal,
                coolingDownSymbols = coolingDownFinal,
                remainingSymbols = remaining,
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyBackfillCampaign 종료: runId={} status={} mode={} wiped={} universe={} alreadyComplete={} processed={} " +
                "newlyComplete={} inserted={} calls={}/{} failed={} coolingDown={} remaining={} durationMs={}",
            runId,
            status,
            mode,
            wipedRows,
            codes.size,
            alreadyCompleteFinal,
            processedFinal,
            newlyCompleteFinal,
            insertedRowsFinal,
            callsConsumedFinal,
            callCap,
            failedFinal,
            coolingDownFinal,
            remaining,
            durationMs,
        )
        audit(
            "MARKET_DAILY_BACKFILL_RUN_DONE",
            campaignAuditEntityId(runId),
            mapOf(
                "runId" to runId,
                "status" to status.name,
                "mode" to mode.name,
                "wipedRows" to wipedRows,
                "universeSize" to codes.size,
                "alreadyComplete" to alreadyCompleteFinal,
                "processed" to processedFinal,
                "newlyComplete" to newlyCompleteFinal,
                "inserted" to insertedRowsFinal,
                "callsConsumed" to callsConsumedFinal,
                "callCap" to callCap,
                "failed" to failedFinal,
                "coolingDown" to coolingDownFinal,
                "remaining" to remaining,
                "durationMs" to durationMs,
            ),
        )
        return result
    }

    /** universe 보통주 카운트 (admin progress 관측 — 콜 0). */
    fun universeCount(): Long = universeProvider.count()

    /** 가동 0 종료(DISABLED·EMPTY_UNIVERSE) 결과 — 0 카운트 + mode·wipedRows·callCap·durationMs 만 채움. */
    private fun emptyResult(
        runId: String,
        status: CampaignStatus,
        mode: BackfillMode,
        wipedRows: Long,
        startMs: Long,
    ): CampaignResult =
        CampaignResult(
            runId = runId,
            status = status,
            mode = mode,
            wipedRows = wipedRows,
            universeSize = 0,
            alreadyComplete = 0,
            processedSymbols = 0,
            newlyCompleteSymbols = 0,
            insertedRows = 0L,
            callsConsumed = 0,
            callCap = properties.normalizedNightlyCallCap(),
            failedSymbols = 0,
            skippedThrottle = 0,
            coolingDownSymbols = 0,
            remainingSymbols = 0,
            durationMs = System.currentTimeMillis() - startMs,
        )

    private sealed interface SymbolOutcome {
        data object Throttled : SymbolOutcome

        data class Done(
            val callsUsed: Int,
            val inserted: Int,
            val complete: Boolean,
        ) : SymbolOutcome

        data class Failed(
            val callsUsed: Int,
        ) : SymbolOutcome
    }

    /**
     * 단일 종목 처리 — throttle gate 통과 → listed_at clamp → range fetch(BACKFILL) → sanity → saveAll.
     *
     * range fetch 는 종목당 페이지네이션(여러 콜) 1 묶음. callsUsed 는 보수적으로 페이지 수 추정(최소 1).
     */
    private fun processSymbol(
        code: String,
        historyFloor: LocalDate,
        toDate: LocalDate,
        runId: String,
        today: LocalDate,
    ): SymbolOutcome {
        // listed_at clamp — 상장 이전 구간 원천 제외(false fetch 0). listed_at null 이면 historyFloor 사용.
        val listedAt: LocalDate? =
            try {
                universeRepository.findListedAtByCode(code)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign listed_at 조회 실패 — historyFloor 사용: code={} err={}", code, e.message)
                null
            }
        val fromDate = maxOf(historyFloor, listedAt ?: historyFloor)
        if (fromDate.isAfter(toDate)) {
            // 상장이 종료 경계 이후(초신규 상장) — 적재 대상 거래일 0. 완료로 간주(uncovered, 재시도 무의미).
            log.debug("MarketDailyBackfillCampaign 상장 이후 거래일 0 — skip: code={} listedAt={} to={}", code, listedAt, toDate)
            return SymbolOutcome.Done(callsUsed = 0, inserted = 0, complete = true)
        }

        // ★ no-advance 가드 baseline — fetch 직전 max_ts 캡처. fetch 후 전진 안 하면 cooldown 마킹.
        val preFetchMaxTs = safeMaxTs(code)

        // circuit-only 가드 — 동시성 제어는 campaign 의 coroutine Semaphore, KIS hard ceiling 은 rate-limiter.
        // serial semaphore(1)·minInterval(1.5s)는 동시성을 무력화하므로 미적용(ADR 0118 amend §동시성).
        val throttledResult =
            backfillThrottleGate.runWithCircuitGuard(code) {
                fetchAndStore(code, fromDate, toDate, runId)
            }
        if (throttledResult == null) {
            return SymbolOutcome.Throttled
        }
        // circuit breaker outcome 보고 — fetch 실패(rate-limit 압박)면 trip 누적.
        backfillThrottleGate.recordRunOutcome(throttledResult.rateLimitPressure)
        if (throttledResult.failed) {
            return SymbolOutcome.Failed(callsUsed = throttledResult.callsUsed)
        }

        val complete = isSymbolComplete(code, historyFloor, toDate)
        if (!complete) {
            // fetch 는 성공(fail-closed 아님)했으나 여전히 미완료. max_ts 가 전진했는지 확인.
            // 전진 0 = KIS 가 새 데이터를 안 줌(KIS-unavailable) → cooldown 마킹(over-fetch 닫기).
            val postFetchMaxTs = safeMaxTs(code)
            if (!advanced(preFetchMaxTs, postFetchMaxTs)) {
                markNoAdvanceCooldown(code, runId, today, preFetchMaxTs, postFetchMaxTs)
            }
        }
        return SymbolOutcome.Done(
            callsUsed = throttledResult.callsUsed,
            inserted = throttledResult.inserted,
            complete = complete,
        )
    }

    private fun safeMaxTs(code: String): Instant? =
        try {
            candleRepository.findMaxTsByCodeAndInterval(code, INTERVAL)
        } catch (e: Exception) {
            log.warn("MarketDailyBackfillCampaign maxTs 조회 실패(no-advance 판정): code={} err={}", code, e.message)
            null
        }

    /** post 가 pre 보다 더 최신이면 전진. pre==null 인데 post 가 생기면 전진(첫 적재). 둘 다 null 이면 전진 0. */
    private fun advanced(
        pre: Instant?,
        post: Instant?,
    ): Boolean {
        if (post == null) return false
        if (pre == null) return true
        return post.isAfter(pre)
    }

    private data class FetchStoreResult(
        val callsUsed: Int,
        val inserted: Int,
        val failed: Boolean,
        val rateLimitPressure: Boolean,
    )

    private fun fetchAndStore(
        code: String,
        fromDate: LocalDate,
        toDate: LocalDate,
        runId: String,
    ): FetchStoreResult {
        // 콜 수 추정 — range/100건 페이지 + 1 (보수). 실제 페이지 수는 어댑터 내부 페이지네이션.
        val estimatedCalls = estimateCalls(fromDate, toDate)
        val fetched: List<Candle> =
            try {
                kisDailyRangePort.fetchDailyCandlesInRange(code, fromDate, toDate)
            } catch (e: KisDailyRangeUnavailableException) {
                log.warn(
                    "MarketDailyBackfillCampaign range fetch 실패(fail-closed): code={} from={} to={} err={}",
                    code,
                    fromDate,
                    toDate,
                    e.message,
                )
                audit(
                    "MARKET_DAILY_BACKFILL_SYMBOL_FAIL",
                    code,
                    mapOf("runId" to runId, "code" to code, "from" to fromDate.toString(), "to" to toDate.toString(), "err" to e.message),
                )
                return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = true, rateLimitPressure = true)
            }

        if (fetched.isEmpty()) {
            // KIS 정상 응답이나 0건(상장 이전·거래 부재) — uncovered(실패 아님). 갭은 갭으로 남김(mock 0).
            log.debug("MarketDailyBackfillCampaign uncovered(0건): code={} from={} to={}", code, fromDate, toDate)
            return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = false, rateLimitPressure = false)
        }

        // sanity check 재사용 (햇징 룰 §1 — 가격 range·OHLC·monotonic). 위반 봉은 적재 0.
        val sane = candleBackfillWorker.sanityCheck(fetched, runId)
        val inserted =
            try {
                // 과거봉 immutable — ON CONFLICT DO NOTHING. source 마킹 = [BACKFILL_SOURCE]
                // (varchar(20) 안전 길이 보장 — companion init require 가드).
                candleRepository.saveAll(sane.map { it.copy(source = BACKFILL_SOURCE) })
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign saveAll 실패: code={} err={}", code, e.message)
                audit(
                    "MARKET_DAILY_BACKFILL_SYMBOL_FAIL",
                    code,
                    mapOf("runId" to runId, "code" to code, "reason" to "saveAll", "err" to e.message),
                )
                return FetchStoreResult(callsUsed = estimatedCalls, inserted = 0, failed = true, rateLimitPressure = false)
            }

        log.info(
            "MarketDailyBackfillCampaign 종목 적재: code={} from={} to={} fetched={} sane={} inserted={}",
            code,
            fromDate,
            toDate,
            fetched.size,
            sane.size,
            inserted,
        )
        return FetchStoreResult(callsUsed = estimatedCalls, inserted = inserted, failed = false, rateLimitPressure = false)
    }

    /**
     * candle-row 기반 progress — 종목이 10년 backfill 완료되었는가 (★ gap-aware, 2026-06-09 hotfix).
     *
     * ## 직전 결함 (root cause) — OR 오판으로 stale-but-rowful 영구 skip
     *   직전 구현은 `rows >= minRows OR recent` 였다(KDoc 은 "둘 다 충족"이라 적혀 모순). 효과:
     *   036930(일봉 5,000행 ≥ 200, max_ts=2026-02-11 로 4개월 stale)이 **row 수 조건만으로 "완료" 오판**
     *   → 루프 `continue` skip → R-BF 가 fetch 시도조차 안 함 → 갭 자력 복구 불가(영구 누락).
     *
     * ## gap-aware 판정 (over-skip 닫기) — recency 를 필수 조건으로 승격
     *   - `recent` = 최신 봉이 toDate 로부터 [recencyGraceDays] 이내. **모든 경우 필수**(OR 의 빠져나갈 구멍 제거).
     *     → 036930 처럼 row 는 많으나 stale 한 종목은 recent=false → **INCOMPLETE** → 재시도.
     *   - `hasEnoughRows` = rows ≥ [completionMinRows]. 정상 장수명 종목의 충분한 이력.
     *   - `coversListedWindow` = listed_at clamp 구간 [max(historyFloor, listed_at)..toDate] 기대 거래일을
     *     [COVERAGE_RATIO] 이상 보유. **신규/단기 상장**(거래일<minRows)이 매 run 영구 재fetch 되는 것을 방지
     *     (직전 OR 의 (b) 가 보호하던 케이스를 recency 필수 하에서 정확히 복원).
     *   - 완료 = `recent AND (hasEnoughRows OR coversListedWindow)`.
     *
     *   신규/단기 상장: 어제까지 거래(recent=true) + clamp 구간 거래일 전수 보유(coversListedWindow=true)
     *   → COMPLETE(매 run 재fetch 안 함). stale-but-rowful: recent=false → INCOMPLETE(재시도). 둘 다 정확.
     */
    fun isSymbolComplete(
        code: String,
        historyFloor: LocalDate,
        toDate: LocalDate,
    ): Boolean {
        val rows =
            try {
                candleRepository.countByCodeAndInterval(code, INTERVAL)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign count 실패 — 미완료 간주: code={} err={}", code, e.message)
                return false
            }
        if (rows == 0L) return false

        val maxTs =
            try {
                candleRepository.findMaxTsByCodeAndInterval(code, INTERVAL)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign maxTs 실패 — 미완료 간주(보수): code={} err={}", code, e.message)
                return false
            }
        // ★ recency 필수 — stale-but-rowful(036930) 을 row 수 만으로 완료 처리하던 OR 구멍 제거.
        val recent =
            maxTs?.let {
                val latestDate = it.atZone(KST_ZONE).toLocalDate()
                !latestDate.isBefore(toDate.minusDays(properties.normalizedRecencyGraceDays()))
            } ?: false
        if (!recent) return false

        val hasEnoughRows = rows >= properties.normalizedCompletionMinRows()
        if (hasEnoughRows) return true

        // 신규/단기 상장 보정 — clamp 구간 기대 거래일 전수(COVERAGE_RATIO) 보유 시 완료(매 run 재fetch 방지).
        val clampStart = maxOf(historyFloor, safeListedAt(code) ?: historyFloor)
        val expectedTradingDays = countWeekdaysInclusive(clampStart, toDate)
        if (expectedTradingDays <= 0L) return true
        return rows.toDouble() >= expectedTradingDays.toDouble() * COVERAGE_RATIO
    }

    /** listed_at 조회 (no-throw — 실패 시 null → historyFloor clamp). */
    private fun safeListedAt(code: String): LocalDate? =
        try {
            universeRepository.findListedAtByCode(code)
        } catch (e: Exception) {
            log.warn("MarketDailyBackfillCampaign listed_at 조회 실패(완료 판정): code={} err={}", code, e.message)
            null
        }

    /** 미완료 종목 수 — run 종료 후 잔여 산출 (status·이연 판단). cooldown 종목도 미완료로 집계(실태 반영). */
    private fun countRemaining(
        codes: List<String>,
        historyFloor: LocalDate,
        toDate: LocalDate,
    ): Int = codes.count { !isSymbolComplete(it, historyFloor, toDate) }

    /** range 콜 수 보수 추정 — 거래일/100건 페이지 + 1. 실제 페이지네이션은 어댑터 내부. */
    private fun estimateCalls(
        from: LocalDate,
        to: LocalDate,
    ): Int {
        val tradingDays = countWeekdaysInclusive(from, to)
        return ((tradingDays + 99) / 100 + 1).toInt().coerceAtLeast(1)
    }

    private fun countWeekdaysInclusive(
        from: LocalDate,
        to: LocalDate,
    ): Long {
        if (from.isAfter(to)) return 0L
        var count = 0L
        var cur = from
        while (!cur.isAfter(to)) {
            val dow = cur.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) count++
            cur = cur.plusDays(1)
        }
        return count
    }

    // ---- no-advance cooldown (over-fetch 가드 · audit 기반 · Flyway 0) -------------------------

    /**
     * 종목이 no-advance cooldown 중인가 — 직전 [ACTION_NO_ADVANCE] 마킹이 cooldown 일수 이내면 true.
     *
     * fetch 했으나 KIS 가 새 데이터를 안 준(max_ts 전진 0) 종목은 cooldown 동안 fetch skip(콜 낭비 방지).
     * cooldown 만료 후 재진입(corporate action 해소 등으로 데이터 출현 시 복구 — 주기적 재확인 유지).
     * cooldownDays=0 또는 마킹 없음 → cooldown 아님(즉시 재시도 가능).
     */
    private fun isInNoAdvanceCooldown(
        code: String,
        today: LocalDate,
    ): Boolean {
        val cooldownDays = properties.normalizedNoAdvanceCooldownDays()
        if (cooldownDays <= 0L) return false
        val lastMarked =
            try {
                auditRepository.findLatestOccurredAt("market_daily_backfill", code, ACTION_NO_ADVANCE)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign no-advance 조회 실패 — cooldown 미적용: code={} err={}", code, e.message)
                return false
            } ?: return false
        val lastDate = lastMarked.atZone(KST_ZONE).toLocalDate()
        // 마킹일 + cooldownDays 가 today 보다 미래면(아직 cooldown 구간) skip.
        return !today.isAfter(lastDate.plusDays(cooldownDays))
    }

    /** no-advance 마킹 — fetch 성공·미완료·max_ts 전진 0(KIS-unavailable) 종목 cooldown 등록. */
    private fun markNoAdvanceCooldown(
        code: String,
        runId: String,
        today: LocalDate,
        preMaxTs: Instant?,
        postMaxTs: Instant?,
    ) {
        log.info(
            "MarketDailyBackfillCampaign no-advance cooldown 마킹 — KIS 신규 데이터 0(전진 없음): " +
                "code={} preMaxTs={} postMaxTs={} cooldownDays={} runId={}",
            code,
            preMaxTs,
            postMaxTs,
            properties.normalizedNoAdvanceCooldownDays(),
            runId,
        )
        // entity_id=code(6자, ≤40 안전). 전체 runId·max_ts 는 detail_json 보존.
        audit(
            ACTION_NO_ADVANCE,
            code,
            mapOf(
                "runId" to runId,
                "code" to code,
                "today" to today.toString(),
                "preMaxTs" to preMaxTs?.toString(),
                "postMaxTs" to postMaxTs?.toString(),
                "cooldownDays" to properties.normalizedNoAdvanceCooldownDays(),
            ),
        )
    }

    /**
     * 캠페인 run audit 의 entity_id — runId(~60자) 대신 짧은 고정 식별자([CAMPAIGN_ENTITY_ID], ≤40).
     * runId 전체는 detail_json 에 보존(추적 가능). audit_trail.entity_id VARCHAR(40) overflow 회귀 방지.
     */
    private fun campaignAuditEntityId(runId: String): String {
        require(CAMPAIGN_ENTITY_ID.length <= AUDIT_ENTITY_ID_MAX_LEN) {
            "CAMPAIGN_ENTITY_ID exceeds audit_trail.entity_id VARCHAR($AUDIT_ENTITY_ID_MAX_LEN)"
        }
        // runId 는 detail_json 으로 들어가므로 entity_id 는 고정 식별자만 사용(run 별 구분은 detail.runId).
        return CAMPAIGN_ENTITY_ID
    }

    private fun audit(
        action: String,
        entityId: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyBackfillCampaign audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        // 방어적 truncate — entity_id 가 VARCHAR(40) 를 넘으면 22001 로 audit 전건 실패(audit 미기록).
        // 정상 경로(campaignAuditEntityId·code)는 이미 ≤40 이나, 회귀 가드로 한 번 더 절단.
        val safeEntityId =
            if (entityId.length > AUDIT_ENTITY_ID_MAX_LEN) entityId.substring(0, AUDIT_ENTITY_ID_MAX_LEN) else entityId
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = "market_daily_backfill",
                    entityId = safeEntityId,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyBackfillCampaign audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
