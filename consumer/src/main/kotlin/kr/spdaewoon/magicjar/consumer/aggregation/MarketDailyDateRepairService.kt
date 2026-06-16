package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisDailyCandleRangePort
import kr.spdaewoon.magicjar.domain.market.port.KisDailyRangeUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kotlinx.datetime.Instant as KtInstant

/**
 * MarketDailyDateRepairService — ADR 0118 R-BF amend (2026-06-15 특정 날짜 1d 정밀 복구).
 *
 * ## 배경 (06-12 전종목 일봉 손상)
 *   2026-06-12 장전 멀티시세 snapshot 이 OHL=0 을 반환 → `candle` interval=`1d` 2026-06-12 row 가
 *   2,517 중 2,516건 손상(open_raw/high_raw/low_raw 중 ≤0, volume=0, close_raw 만 정상,
 *   source=`KIS-MKT-DAILY-SNAP`). 근본 원인(0값 가드)은 이미 main 머지·배포되어 추가 오염은 차단됨.
 *   본 서비스는 **이미 손상된 그 날짜 1d row 만** KIS 공식값으로 교정한다.
 *
 * ## 기존 캠페인과의 차이 (왜 별도 service 인가 — direction-guard)
 *   - [MarketDailyBackfillCampaign] NORMAL: 멱등 재개·손상 row skip(완료 종목으로 오판) → 06-12 못 고침.
 *   - [MarketDailyBackfillCampaign] MIGRATE: 일봉 패밀리(1d·1wk·1mo·1y) **전체 wipe** → 10년 재적재.
 *     장중 자동매매 일봉 토대를 흔들어 운영 중 부적합(전종목 며칠 분산 재적재).
 *   - 본 service: **대상 날짜 1d 1봉만** KIS daily range 로 재fetch → upsert(DO UPDATE) 로 손상 row 덮음.
 *     1d 패밀리 전체 wipe 0. 다른 날짜 1d 무영향. 손상이 전파됐을 그 날짜 소속 주/월/년봉만 재롤업.
 *
 * ## 동작 (종목별)
 *   1. KIS daily range `[date, date]` 재fetch ([KisDailyCandleRangePort.fetchDailyCandlesInRange],
 *      BACKFILL 버킷 — USER_PATH·INGESTION·주문 경로 침범 0).
 *   2. 응답 중 대상 날짜(KST 자정 ts)에 해당하는 1봉 추출.
 *      - 정상 봉(OHLC 전부 >0): [CandleRepository.upsertAll] 로 그 1봉만 DO UPDATE(손상 row 덮음).
 *      - KIS 가 그 날짜를 0건 반환(무거래·거래정지·신규상장 직전) 또는 추출 봉이 여전히 OHL ≤0:
 *        upsert 로 덮을 정상값이 없으므로 **기존 손상 row 를 정확 ts 1행 DELETE**(손상 잔존 금지 · mock 합성 0).
 *   3. 대상 날짜 소속 주/월/년봉 재롤업 — [DailyFamilyRollupRunner.rollupDetailed](full-regen)로 1d 교정 반영.
 *
 * ## rate-limit (ADR 0118 §5-1 LOCK 계승)
 *   모든 KIS 콜은 [KisDailyCandleRangePort] 어댑터가 BACKFILL(BG) 버킷 고정. coroutine Semaphore 로 동시
 *   종목 수 제한 + 종목 launch 당 spacing 으로 burst 분산. circuit breaker([BackfillThrottleGate]) 보존.
 *   전종목 ~2,517콜이지만 BG 격리 — 자본·사용자 path 영향 0.
 *
 * ## 안전 (add-only · enabled 게이트 계승)
 *   - [MarketDailyBackfillCampaign] NORMAL/MIGRATE 경로 **무변경**(add-only).
 *   - [MarketDailyBackfillProperties.enabled] off → 즉시 DISABLED 종료(가동 0). 기존 게이트 그대로 계승.
 *   - 비동기 실행([repairDateAsync]) — 즉시 runId 반환 + 백그라운드 완주(curl 타임아웃 회피).
 *
 * 자본 영향 0 — 데이터 교정만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only).
 */
@Component
class MarketDailyDateRepairService(
    private val universeProvider: MarketDailyUniverseProvider,
    private val kisDailyRangePort: KisDailyCandleRangePort,
    private val candleRepository: CandleRepository,
    private val rollupRunner: DailyFamilyRollupRunner,
    private val backfillThrottleGate: BackfillThrottleGate,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
    private val properties: MarketDailyBackfillProperties,
) {
    private val log = LoggerFactory.getLogger(MarketDailyDateRepairService::class.java)

    companion object {
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val INTERVAL = CandleInterval.ONE_DAY

        /** audit_trail.entity_id VARCHAR(40) 한계 — 짧은 고정 식별자(전종목 run). runId·종목은 detail. */
        const val AUDIT_ENTITY_ID = "mkt-daily-repair"
        const val AUDIT_ENTITY_TYPE = "market_daily_date_repair"
    }

    /** 백그라운드 비동기 실행 scope — admin 즉시 응답 후 별도 스레드에서 완주. */
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 진행 중 여부 — 중복 비동기 트리거 방지(동시 1건만). */
    private val running = AtomicReference(false)

    /** 마지막 완료/진행 결과 — admin 상태조회 관측용. */
    private val lastResult = AtomicReference<RepairResult?>(null)

    enum class RepairStatus {
        DISABLED, // ENV enabled=false — 가동 0
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
        ALREADY_RUNNING, // 이미 비동기 실행 중 — 중복 트리거 거부(멱등 보호)
        COMPLETED, // 정상 완주(또는 백그라운드 진행 중 accepted)
    }

    data class RepairResult(
        val runId: String,
        val status: RepairStatus,
        val targetDate: String,
        val universeSize: Int,
        val processedSymbols: Int, // KIS fetch 시도한 종목 수(throttle skip·실패 제외)
        val repairedSymbols: Int, // 정상값으로 upsert(교정)된 종목 수
        val upsertedRows: Long, // 교정 upsert row 수(종목당 1d 1봉 → repairedSymbols 와 같음)
        val deletedCorruptRows: Long, // KIS 미보유·여전히 0값이라 DELETE 한 손상 row 수
        val stillZeroSymbols: Int, // upsert 후에도(=DELETE 도 실패) 0값 잔존 가능성 — 진단용
        val failedSymbols: Int, // KIS unavailable·throttle·예외로 처리 못한 종목 수
        val rollupWeeklyUpserted: Long,
        val rollupMonthlyUpserted: Long,
        val rollupYearlyUpserted: Long,
        val durationMs: Long,
    )

    data class RepairStatusView(
        val running: Boolean,
        val lastResult: RepairResult?,
    )

    fun statusView(): RepairStatusView = RepairStatusView(running = running.get(), lastResult = lastResult.get())

    /**
     * 비동기 트리거 — 즉시 runId 반환 후 백그라운드에서 [repairDate] 완주(curl 타임아웃 회피).
     *
     * 동시 1건만 허용(running CAS). enabled off·universe 0 은 동기로 즉시 판정(백그라운드 launch 0).
     */
    fun repairDateAsync(targetDate: LocalDate): RepairResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-repair:$startMs"

        if (!properties.enabled) {
            log.info("MarketDailyDateRepair DISABLED — ENV magicjar.market-daily-backfill.enabled=false (가동 0)")
            return emptyResult(runId, RepairStatus.DISABLED, targetDate, 0, startMs)
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("MarketDailyDateRepair ALREADY_RUNNING — 비동기 실행 중복 트리거 거부: runId={}", runId)
            return emptyResult(runId, RepairStatus.ALREADY_RUNNING, targetDate, 0, startMs)
        }

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            running.set(false)
            log.warn("MarketDailyDateRepair EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_REPAIR_EMPTY", mapOf("runId" to runId, "targetDate" to targetDate.toString()))
            return emptyResult(runId, RepairStatus.EMPTY_UNIVERSE, targetDate, 0, startMs)
        }

        // accepted(202 의미) — universeSize 까지만 채운 즉시 응답. 완주 결과는 GET /status 로 관측.
        val accepted =
            emptyResult(runId, RepairStatus.COMPLETED, targetDate, codes.size, startMs)
        lastResult.set(accepted)

        asyncScope.launch {
            try {
                val result = repairInternal(runId, targetDate, codes, startMs)
                lastResult.set(result)
            } catch (e: Exception) {
                log.warn("MarketDailyDateRepair 비동기 실행 실패: runId={} err={}", runId, e.message, e)
            } finally {
                running.set(false)
            }
        }
        return accepted
    }

    /**
     * 전종목 특정 날짜 1d 복구 1회 **동기** 실행 — 테스트·내부 호출 진입점.
     * admin 은 [repairDateAsync] 경유(타임아웃 회피). enabled off·universe 0 시 해당 status 반환.
     */
    fun repairDate(targetDate: LocalDate): RepairResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-repair:$startMs"

        if (!properties.enabled) {
            log.info("MarketDailyDateRepair DISABLED — ENV magicjar.market-daily-backfill.enabled=false (가동 0)")
            return emptyResult(runId, RepairStatus.DISABLED, targetDate, 0, startMs)
        }
        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyDateRepair EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_REPAIR_EMPTY", mapOf("runId" to runId, "targetDate" to targetDate.toString()))
            return emptyResult(runId, RepairStatus.EMPTY_UNIVERSE, targetDate, 0, startMs)
        }
        return repairInternal(runId, targetDate, codes, startMs)
    }

    /**
     * 전종목 병렬 복구 코어 — coroutine Semaphore([MarketDailyBackfillProperties.concurrency]) 동시 실행.
     *
     * 종목별: KIS daily range `[date,date]` 재fetch → 대상 날짜 1봉 추출 → upsert(정상) 또는 DELETE(0값/미보유)
     * → 대상 날짜 소속 주/월/년봉 재롤업. KIS hard ceiling 은 어댑터 BACKFILL 버킷이 그대로 유효.
     */
    private fun repairInternal(
        runId: String,
        targetDate: LocalDate,
        codes: List<String>,
        startMs: Long,
    ): RepairResult {
        // 대상 날짜 1d 봉 앵커 ts — KIS 1d 봉 관례(KST 자정) 정합(KisRestCandleAdapter 와 동일 구성).
        val targetTs: Instant = targetDate.atStartOfDay(KST_ZONE).toInstant()
        val concurrency = properties.normalizedConcurrency()
        val launchDelayMs = properties.normalizedLaunchDelayMs()

        log.info(
            "MarketDailyDateRepair 시동: runId={} targetDate={} targetTs={} universe={}종목 concurrency={} launchDelayMs={} (BACKFILL 버킷)",
            runId,
            targetDate,
            targetTs,
            codes.size,
            concurrency,
            launchDelayMs,
        )
        audit(
            "MARKET_DAILY_REPAIR_START",
            mapOf(
                "runId" to runId,
                "targetDate" to targetDate.toString(),
                "universeSize" to codes.size,
                "concurrency" to concurrency,
            ),
        )

        val processed = LongAdder()
        val repaired = LongAdder()
        val upsertedRows = LongAdder()
        val deletedCorrupt = LongAdder()
        val stillZero = LongAdder()
        val failed = LongAdder()
        val weekly = LongAdder()
        val monthly = LongAdder()
        val yearly = LongAdder()

        runBlocking {
            val gate = Semaphore(concurrency)
            val dispatcher = Dispatchers.IO.limitedParallelism(concurrency)
            val jobs = mutableListOf<Job>()
            for (code in codes) {
                if (launchDelayMs > 0L) delay(launchDelayMs) // burst 분산
                jobs +=
                    launch(dispatcher) {
                        gate.withPermit {
                            when (val outcome = repairSymbol(code, targetDate, targetTs, runId)) {
                                is SymbolOutcome.Failed -> failed.increment()
                                is SymbolOutcome.Done -> {
                                    processed.increment()
                                    if (outcome.repaired) {
                                        repaired.increment()
                                        upsertedRows.add(outcome.upserted.toLong())
                                    }
                                    deletedCorrupt.add(outcome.deleted.toLong())
                                    if (outcome.stillZero) stillZero.increment()
                                    weekly.add(outcome.weeklyUpserted.toLong())
                                    monthly.add(outcome.monthlyUpserted.toLong())
                                    yearly.add(outcome.yearlyUpserted.toLong())
                                }
                            }
                        }
                    }
            }
            jobs.joinAll()
        }

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            RepairResult(
                runId = runId,
                status = RepairStatus.COMPLETED,
                targetDate = targetDate.toString(),
                universeSize = codes.size,
                processedSymbols = processed.sum().toInt(),
                repairedSymbols = repaired.sum().toInt(),
                upsertedRows = upsertedRows.sum(),
                deletedCorruptRows = deletedCorrupt.sum(),
                stillZeroSymbols = stillZero.sum().toInt(),
                failedSymbols = failed.sum().toInt(),
                rollupWeeklyUpserted = weekly.sum(),
                rollupMonthlyUpserted = monthly.sum(),
                rollupYearlyUpserted = yearly.sum(),
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyDateRepair 종료: runId={} targetDate={} universe={} processed={} repaired={} upserted={} " +
                "deletedCorrupt={} stillZero={} failed={} rollup(wk/mo/y)={}/{}/{} durationMs={}",
            runId,
            targetDate,
            result.universeSize,
            result.processedSymbols,
            result.repairedSymbols,
            result.upsertedRows,
            result.deletedCorruptRows,
            result.stillZeroSymbols,
            result.failedSymbols,
            result.rollupWeeklyUpserted,
            result.rollupMonthlyUpserted,
            result.rollupYearlyUpserted,
            result.durationMs,
        )
        audit(
            "MARKET_DAILY_REPAIR_DONE",
            mapOf(
                "runId" to runId,
                "targetDate" to targetDate.toString(),
                "universeSize" to result.universeSize,
                "processed" to result.processedSymbols,
                "repaired" to result.repairedSymbols,
                "upserted" to result.upsertedRows,
                "deletedCorrupt" to result.deletedCorruptRows,
                "stillZero" to result.stillZeroSymbols,
                "failed" to result.failedSymbols,
                "rollupWeekly" to result.rollupWeeklyUpserted,
                "rollupMonthly" to result.rollupMonthlyUpserted,
                "rollupYearly" to result.rollupYearlyUpserted,
                "durationMs" to result.durationMs,
            ),
        )
        return result
    }

    private sealed interface SymbolOutcome {
        data object Failed : SymbolOutcome

        data class Done(
            val repaired: Boolean,
            val upserted: Int,
            val deleted: Int,
            val stillZero: Boolean,
            val weeklyUpserted: Int,
            val monthlyUpserted: Int,
            val yearlyUpserted: Int,
        ) : SymbolOutcome
    }

    /**
     * 단일 종목 대상 날짜 1d 복구 — KIS range 재fetch → upsert(정상) 또는 DELETE(0값/미보유) → 주/월/년 재롤업.
     *
     * circuit breaker 가드([BackfillThrottleGate.runWithCircuitGuard])로 KIS 압박 시 자동 차단. KIS hard
     * ceiling 은 어댑터 BACKFILL 버킷. 동시성은 호출자 coroutine Semaphore.
     */
    private fun repairSymbol(
        code: String,
        targetDate: LocalDate,
        targetTs: Instant,
        runId: String,
    ): SymbolOutcome {
        val fetched: List<Candle>? =
            backfillThrottleGate.runWithCircuitGuard(code) {
                try {
                    // KIS daily range [date, date] — BACKFILL 버킷(어댑터 §5-1 LOCK). 대상 날짜 1봉만 기대.
                    kisDailyRangePort.fetchDailyCandlesInRange(code, targetDate, targetDate)
                } catch (e: KisDailyRangeUnavailableException) {
                    log.warn("MarketDailyDateRepair range fetch 실패(fail-closed): code={} date={} err={}", code, targetDate, e.message)
                    null
                }
            }
        if (fetched == null) {
            // circuit OPEN 또는 KIS unavailable — 교정 못 함(다음 실행 재시도). 손상 row 는 보존(잘못 삭제 금지).
            audit(
                "MARKET_DAILY_REPAIR_SYMBOL_FAIL",
                mapOf("runId" to runId, "code" to code, "targetDate" to targetDate.toString()),
                entityId = code,
            )
            return SymbolOutcome.Failed
        }

        // 대상 날짜(앵커 ts) 정확 일치 봉 추출. KIS range 가 인접일을 포함해도 대상 날짜 1봉만 교정한다.
        val targetCandle = fetched.firstOrNull { it.ts == KtInstant.fromEpochSeconds(targetTs.epochSecond) }

        var upserted = 0
        var deleted = 0
        var repaired = false
        var stillZero = false

        if (targetCandle != null && isValidOhlc(targetCandle)) {
            // 정상값 — 그 1봉만 DO UPDATE(손상 row 덮음). source 는 KIS range 적재 컨벤션 계승.
            val repairBar =
                targetCandle.copy(interval = INTERVAL, source = MarketDailyBackfillCampaign.BACKFILL_SOURCE)
            upserted =
                try {
                    candleRepository.upsertAll(listOf(repairBar))
                } catch (e: Exception) {
                    log.warn("MarketDailyDateRepair upsert 실패: code={} date={} err={}", code, targetDate, e.message)
                    audit(
                        "MARKET_DAILY_REPAIR_SYMBOL_FAIL",
                        mapOf(
                            "runId" to runId,
                            "code" to code,
                            "targetDate" to targetDate.toString(),
                            "reason" to "upsert",
                            "err" to e.message,
                        ),
                        entityId = code,
                    )
                    return SymbolOutcome.Failed
                }
            repaired = upserted > 0
            log.info("MarketDailyDateRepair 교정: code={} date={} upserted={} ohlc={}", code, targetDate, upserted, ohlcStr(targetCandle))
        } else {
            // KIS 가 대상 날짜를 0건 반환(무거래·거래정지·신규상장 직전) 또는 여전히 0값 — 정상값으로 덮을 수 없음.
            // 손상 0값 row 잔존 금지(mock 합성 0) → 그 정확한 1행만 DELETE.
            deleted =
                try {
                    candleRepository.deleteByCodeAndIntervalAndTs(code, INTERVAL, targetTs)
                } catch (e: Exception) {
                    log.warn("MarketDailyDateRepair DELETE 실패: code={} date={} err={}", code, targetDate, e.message)
                    audit(
                        "MARKET_DAILY_REPAIR_SYMBOL_FAIL",
                        mapOf(
                            "runId" to runId,
                            "code" to code,
                            "targetDate" to targetDate.toString(),
                            "reason" to "delete",
                            "err" to e.message,
                        ),
                        entityId = code,
                    )
                    return SymbolOutcome.Failed
                }
            stillZero = deleted == 0 // 삭제 대상 row 가 애초에 없으면(이미 정상이거나 row 없음) 0값 잔존 아님 — 진단 참고
            log.info(
                "MarketDailyDateRepair 미보유/0값 — 손상 row DELETE: code={} date={} deleted={} kisReturned={} (mock 합성 0)",
                code,
                targetDate,
                deleted,
                fetched.size,
            )
        }

        // 대상 날짜 소속 주/월/년봉 재롤업 — 06-12 손상이 1wk/1mo/1y 에 전파됐을 수 있으므로 full-regen 으로 1d 교정 반영.
        // KIS 콜 0(보유 1d 합산). 종목당 가벼움(<10ms 예상). 롤업 실패는 1d 교정을 무효화하지 않음(흡수).
        var wk = 0
        var mo = 0
        var yr = 0
        try {
            val rollup = rollupRunner.rollupDetailed(code, null) // full-regen — 전 history 정합 보증
            wk = rollup[CandleInterval.ONE_WEEK.code]?.upserted?.coerceAtLeast(0) ?: 0
            mo = rollup[CandleInterval.ONE_MONTH.code]?.upserted?.coerceAtLeast(0) ?: 0
            yr = rollup[CandleInterval.ONE_YEAR.code]?.upserted?.coerceAtLeast(0) ?: 0
        } catch (e: Exception) {
            log.warn("MarketDailyDateRepair 롤업 실패(1d 교정은 유효): code={} date={} err={}", code, targetDate, e.message)
        }

        return SymbolOutcome.Done(
            repaired = repaired,
            upserted = upserted,
            deleted = deleted,
            stillZero = stillZero,
            weeklyUpserted = wk,
            monthlyUpserted = mo,
            yearlyUpserted = yr,
        )
    }

    /** OHLC 4값 모두 >0 이면 정상 봉. 손상(open/high/low ≤0) 봉은 upsert 대상 제외(DELETE 경로). */
    private fun isValidOhlc(c: Candle): Boolean =
        c.openRaw > BigDecimal.ZERO &&
            c.highRaw > BigDecimal.ZERO &&
            c.lowRaw > BigDecimal.ZERO &&
            c.closeRaw > BigDecimal.ZERO

    private fun ohlcStr(c: Candle): String = "O=${c.openRaw} H=${c.highRaw} L=${c.lowRaw} C=${c.closeRaw} V=${c.volume}"

    private fun emptyResult(
        runId: String,
        status: RepairStatus,
        targetDate: LocalDate,
        universeSize: Int,
        startMs: Long,
    ): RepairResult =
        RepairResult(
            runId = runId,
            status = status,
            targetDate = targetDate.toString(),
            universeSize = universeSize,
            processedSymbols = 0,
            repairedSymbols = 0,
            upsertedRows = 0L,
            deletedCorruptRows = 0L,
            stillZeroSymbols = 0,
            failedSymbols = 0,
            rollupWeeklyUpserted = 0L,
            rollupMonthlyUpserted = 0L,
            rollupYearlyUpserted = 0L,
            durationMs = System.currentTimeMillis() - startMs,
        )

    private fun audit(
        action: String,
        detail: Map<String, Any?>,
        entityId: String = AUDIT_ENTITY_ID,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyDateRepair audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = AUDIT_ENTITY_TYPE,
                    entityId = entityId,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyDateRepair audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
