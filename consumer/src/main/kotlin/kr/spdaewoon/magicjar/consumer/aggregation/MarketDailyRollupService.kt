package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * MarketDailyRollupService — ADR 0120 전종목 1wk/1mo/1y 1d 롤업 백필 본체.
 *
 * ## 배경 (년봉 회귀 — 2026-06-09 마이그레이션 부작용)
 *   MIGRATE wipe 가 일봉 패밀리 4종(1d·1wk·1mo·1y) 전부 삭제했으나 R-BF 캠페인은 1d 만 재적재
 *   → 실측 1wk·1mo·1y 가 15종목만 남음(전종목 비었음, 차트 년봉 안 나옴). 본 서비스가 전종목 1d 에서
 *   1wk/1mo/1y 를 합산 적재하여 복구한다. **KIS 콜 0** (이미 보유한 1d 10년 전종목 합산).
 *
 * ## 성능 최적화 (ADR 0120 amend — 2026-06-10 실측 결함 fix)
 *   초판은 종목별 순차 + 종목당 ~2,000봉 row-by-row upsert → ~3종목/분(curl 10분 타임아웃에 45/2,517 만).
 *   - **batch upsert**: [ConsumerCandleRepositoryAdapter.upsertAll] 을 JDBC batchUpdate 로 전환(round-trip 축소).
 *   - **동시성**: 코루틴 Semaphore([CONCURRENCY]) 병렬 — KIS 콜 0(순수 DB+CPU)이라 rate-limiter 무관, hikari pool(5) 대비 보수.
 *   - **비동기 admin**: [runRollupAsync] 즉시 runId 반환 + 백그라운드 실행 → curl 타임아웃 회피(진행은 로그·DB audit·[lastResult]).
 *
 * ## 책임 (한 PR = 한 영역 · market-data)
 *   1. [MarketDailyUniverseProvider.resolveCodes] 보통주 ~2,517 코드 확보 (R1·R-BF 재사용).
 *   2. 종목별 [DailyFamilyRollupRunner.rollupDetailed] — 1d **전량** in-memory 롤업 → upsert(DO UPDATE).
 *      admin 은 full-regen(recentDays=null) 고정 — 1회성 복구 + 수동 재실행은 전 history 정합 보증.
 *   3. 멱등 — upsertAll(ON CONFLICT DO UPDATE) 이므로 재실행 시 행 안 늘고 값만 갱신.
 *   4. 관측 로그 + audit (처리 종목수·upsert 건수·실패·소요).
 *
 * ## R-BF / R2 / 본 서비스 분업 (ADR 0120 §2)
 *   - R-BF(MarketDailyBackfillCampaign): 1d 과거 10년 KIS range fetch (기준봉 적재).
 *   - 본 서비스(rollup, admin): R-BF 가 채운 1d 에서 1wk/1mo/1y 파생 합산 **full-regen**(1회 복구 + 수동 재실행).
 *   - R2(MarketDailyNightlySnapshotService): 매 영업일 today 1d snapshot 직후 1wk/1mo/1y **incremental** 재롤업.
 *
 * 자본 영향 0 — 데이터 적재만(주문 0). LLM import 0 (룰 5). 외부 fallback 0 (KIS-only · KIS 콜 0).
 */
@Component
class MarketDailyRollupService(
    private val universeProvider: MarketDailyUniverseProvider,
    private val rollupRunner: DailyFamilyRollupRunner,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketDailyRollupService::class.java)

    companion object {
        /** audit_trail.entity_id VARCHAR(40) 한계 — 짧은 고정 식별자. runId 는 detail. */
        const val AUDIT_ENTITY_ID = "mkt-daily-rollup"
        const val AUDIT_ENTITY_TYPE = "market_daily_rollup"

        /**
         * 종목 병렬 동시성 — KIS 콜 0(순수 DB+CPU)이라 rate-limiter 무관.
         * hikari maximum-pool-size=5(consumer application.yml) 대비 보수적 4(audit/스케줄러 트랜잭션 여유 1).
         * batchUpdate 1 종목 = 1 connection 점유이므로 4 동시 점유여도 pool 고갈 안 함.
         */
        const val CONCURRENCY = 4
    }

    /** 백그라운드 비동기 실행 scope — admin 즉시 응답 후 별도 스레드에서 완주(앱 생명주기 동안 유지). */
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 진행 중 여부 — 중복 비동기 트리거 방지(동시 1건만). */
    private val running = AtomicReference(false)

    /** 마지막 완료/진행 결과 — admin 상태조회(GET /status) 관측용. */
    private val lastResult = AtomicReference<RollupResult?>(null)

    enum class RollupStatus {
        EMPTY_UNIVERSE, // universe 0종목 (데이터 없음 path — mock 합성 0)
        ALREADY_RUNNING, // 이미 비동기 실행 중 — 중복 트리거 거부(멱등 보호)
        COMPLETED, // 정상 완주
    }

    data class RollupResult(
        val runId: String,
        val status: RollupStatus,
        val universeSize: Int,
        val processedSymbols: Int,
        val skippedNoDaily: Int,
        val weeklyUpserted: Long,
        val monthlyUpserted: Long,
        val yearlyUpserted: Long,
        val failedSymbols: Int,
        val durationMs: Long,
    )

    /** admin 상태조회 응답 — 진행 중 여부 + 마지막 결과(없으면 null). */
    data class RollupStatusView(
        val running: Boolean,
        val lastResult: RollupResult?,
    )

    fun statusView(): RollupStatusView = RollupStatusView(running = running.get(), lastResult = lastResult.get())

    /**
     * 비동기 트리거 — 즉시 runId 반환 후 백그라운드에서 [runRollup] 완주(curl 10분 타임아웃 회피).
     *
     * 동시 1건만 허용(running CAS) — 이미 실행 중이면 ALREADY_RUNNING 즉시 반환(중복 보호).
     * universe 0 은 동기로 즉시 판정(백그라운드 launch 0).
     */
    fun runRollupAsync(): RollupResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-rollup:$startMs"

        if (!running.compareAndSet(false, true)) {
            log.warn("MarketDailyRollup ALREADY_RUNNING — 비동기 실행 중복 트리거 거부: runId={}", runId)
            return RollupResult(runId, RollupStatus.ALREADY_RUNNING, 0, 0, 0, 0, 0, 0, 0, 0L)
        }

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            running.set(false)
            log.warn("MarketDailyRollup EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_ROLLUP_EMPTY", mapOf("runId" to runId))
            return RollupResult(runId, RollupStatus.EMPTY_UNIVERSE, 0, 0, 0, 0, 0, 0, 0, System.currentTimeMillis() - startMs)
        }

        // ACCEPTED 진행 상태를 lastResult 에 선반영(GET /status 관측). 백그라운드 완주 시 최종값으로 교체.
        val accepted = RollupResult(runId, RollupStatus.COMPLETED, codes.size, 0, 0, 0, 0, 0, 0, 0L)
        lastResult.set(accepted)

        asyncScope.launch {
            try {
                val result = runRollupInternal(runId, codes, startMs)
                lastResult.set(result)
            } catch (e: Exception) {
                log.warn("MarketDailyRollup 비동기 실행 실패: runId={} err={}", runId, e.message, e)
            } finally {
                running.set(false)
            }
        }
        // accepted (202 의미) — universeSize 까지만 채운 즉시 응답. 완주 결과는 GET /status 로 관측.
        return accepted
    }

    /**
     * 전종목 1wk/1mo/1y 롤업 1회 동기 실행 — 1d full-regen 합산 적재(KIS 콜 0).
     *
     * 매 호출 멱등(upsert DO UPDATE) — 재호출 시 행 안 늘고 값만 갱신. universe 0 시 EMPTY_UNIVERSE.
     * 동기 진입점(테스트·내부 호출). admin 은 [runRollupAsync] 경유(타임아웃 회피).
     */
    fun runRollup(): RollupResult {
        val startMs = System.currentTimeMillis()
        val runId = "mkt-daily-rollup:$startMs"

        val codes = universeProvider.resolveCodes()
        if (codes.isEmpty()) {
            log.warn("MarketDailyRollup EMPTY_UNIVERSE — 보통주 0종목 (데이터 없음 — mock 합성 0)")
            audit("MARKET_DAILY_ROLLUP_EMPTY", mapOf("runId" to runId))
            return RollupResult(runId, RollupStatus.EMPTY_UNIVERSE, 0, 0, 0, 0, 0, 0, 0, System.currentTimeMillis() - startMs)
        }
        return runRollupInternal(runId, codes, startMs)
    }

    /**
     * 전종목 병렬 롤업 코어 — 코루틴 Semaphore([CONCURRENCY]) 동시 실행(KIS 콜 0 — rate-limiter 무관).
     *
     * 종목별 [DailyFamilyRollupRunner.rollupDetailed](recentDays=null · full-regen) 호출 + 집계.
     * 카운터는 [LongAdder]/atomic 으로 병렬 안전 누적. blocking JDBC 는 Dispatchers.IO 격리.
     */
    private fun runRollupInternal(
        runId: String,
        codes: List<String>,
        startMs: Long,
    ): RollupResult {
        log.info(
            "MarketDailyRollup 시동: runId={} universe={}종목 concurrency={} (1wk/1mo/1y ← 1d full-regen 합산, KIS 콜 0)",
            runId,
            codes.size,
            CONCURRENCY,
        )
        audit("MARKET_DAILY_ROLLUP_START", mapOf("runId" to runId, "universeSize" to codes.size, "concurrency" to CONCURRENCY))

        val processed = LongAdder()
        val skippedNoDaily = LongAdder()
        val weekly = LongAdder()
        val monthly = LongAdder()
        val yearly = LongAdder()
        val failed = LongAdder()

        // coroutine Semaphore = 동시 종목 수 제한(throughput shaping). KIS 콜 0 이라 rate-limiter 무관,
        // hikari pool(5) 보호만 목적. blocking JDBC batchUpdate 는 Dispatchers.IO.limitedParallelism 에서 격리.
        runBlocking {
            val gate = Semaphore(CONCURRENCY)
            val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)
            val jobs =
                codes.map { code ->
                    launch(dispatcher) {
                        gate.withPermit {
                            val detailed =
                                try {
                                    rollupRunner.rollupDetailed(code, null) // recentDays=null → full-regen
                                } catch (e: Exception) {
                                    log.warn("MarketDailyRollup 종목 롤업 실패(skip): runId={} code={} err={}", runId, code, e.message)
                                    failed.increment()
                                    return@withPermit
                                }
                            if (detailed.isEmpty()) {
                                skippedNoDaily.increment()
                                return@withPermit
                            }
                            processed.increment()
                            var symbolFailed = false
                            detailed.forEach { (interval, r) ->
                                if (r.upserted < 0) {
                                    symbolFailed = true
                                    return@forEach
                                }
                                when (interval) {
                                    CandleInterval.ONE_WEEK.code -> weekly.add(r.upserted.toLong())
                                    CandleInterval.ONE_MONTH.code -> monthly.add(r.upserted.toLong())
                                    CandleInterval.ONE_YEAR.code -> yearly.add(r.upserted.toLong())
                                }
                            }
                            if (symbolFailed) failed.increment()
                        }
                    }
                }
            jobs.forEach { it.join() }
        }

        val durationMs = System.currentTimeMillis() - startMs
        val result =
            RollupResult(
                runId = runId,
                status = RollupStatus.COMPLETED,
                universeSize = codes.size,
                processedSymbols = processed.sum().toInt(),
                skippedNoDaily = skippedNoDaily.sum().toInt(),
                weeklyUpserted = weekly.sum(),
                monthlyUpserted = monthly.sum(),
                yearlyUpserted = yearly.sum(),
                failedSymbols = failed.sum().toInt(),
                durationMs = durationMs,
            )

        log.info(
            "MarketDailyRollup 종료: runId={} universe={} processed={} skippedNoDaily={} " +
                "weekly={} monthly={} yearly={} failed={} durationMs={}",
            runId,
            result.universeSize,
            result.processedSymbols,
            result.skippedNoDaily,
            result.weeklyUpserted,
            result.monthlyUpserted,
            result.yearlyUpserted,
            result.failedSymbols,
            result.durationMs,
        )
        audit(
            "MARKET_DAILY_ROLLUP_DONE",
            mapOf(
                "runId" to runId,
                "status" to result.status.name,
                "universeSize" to result.universeSize,
                "processed" to result.processedSymbols,
                "skippedNoDaily" to result.skippedNoDaily,
                "weeklyUpserted" to result.weeklyUpserted,
                "monthlyUpserted" to result.monthlyUpserted,
                "yearlyUpserted" to result.yearlyUpserted,
                "failed" to result.failedSymbols,
                "durationMs" to result.durationMs,
            ),
        )
        return result
    }

    private fun audit(
        action: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("MarketDailyRollup audit JSON 직렬화 실패: action={} err={}", action, e.message)
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "market-data",
                    action = action,
                    entityType = AUDIT_ENTITY_TYPE,
                    entityId = AUDIT_ENTITY_ID,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("MarketDailyRollup audit 저장 실패: action={} err={}", action, e.message)
        }
    }
}
