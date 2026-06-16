package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.CandleReconciliationStateStore
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleGapDetectorPort
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * CandleBackfillWorker — T-CC Phase 2-A (ADR 0086 §2-2 + 햇징 룰 10건 본체).
 *
 * 책임:
 *   - CandleGapDetectorPort 로 갭 list 획득 (영업일 캘린더 기반 — 햇징 룰 #6·#7)
 *   - 갭만 정밀 backfill (KIS REST endpoint 재호출 — KisRestPort baseline 100% 재사용)
 *   - 햇징 룰 10건 적용 — sanity check·dry-run·rollback·idempotency·source-of-truth·retry·audit·KillSwitch·운영자 우회
 *
 * KIS endpoint raw 출처 (KIS-first 의무 — 헌법 §3):
 *   - 일봉: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/
 *     /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice (tr_id FHKST03010100)
 *   - 1분봉: /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice (tr_id FHKST03010200)
 *   - Phase 1 baseline 100% 재사용 (KisRestPort.fetchCandles) — 신규 endpoint 호출 0 LOCK.
 *
 * 진입점:
 *   - admin: CandleBackfillAdminController.trigger() (POST /admin/candle-curator/backfill)
 *   - 자동: LastCandleAtMonitor (1 line edit 권장 — 자율 결정)
 *
 * 영역 (ADR 0085 + ADR 0086):
 *   - chart-curator 단일 owner
 *   - market-data·strategy-engine·frontend 0 touch
 *
 * 룰 5 위반 0: LLM provider import 0건 (ADR 0023 정합).
 * 자본 영향 0 — 데이터 정합 보강만 + 주문 발행 0건.
 */
@Component
class CandleBackfillWorker(
    private val candleGapDetector: CandleGapDetectorPort,
    private val candleRepository: CandleRepository,
    private val kisRestPort: KisRestPort,
    private val instrumentJpaRepository: ConsumerInstrumentJpaRepository,
    private val auditRepository: ConsumerAuditTrailJpaRepository,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val reconciliationStateStore: CandleReconciliationStateStore,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val killSwitchJpaRepositoryProvider: ObjectProvider<KillSwitchJpaRepository>,
    private val objectMapper: ObjectMapper,
    // rate-limit safety #1·#2·#3 (2026-06-04 dogfooding fix) — 회복 불가 갭 가드 + 갭 cap + 동시 throttle.
    private val backfillGapGuardStore: BackfillGapGuardStore,
    private val backfillThrottleGate: BackfillThrottleGate,
    private val chartCandleProperties: kr.spdaewoon.magicjar.consumer.config.ChartCandleProperties,
    @Value("\${magicjar.chart-curator.backfill.dry-run:false}")
    private val dryRunPropertyDefault: Boolean,
    @Value("\${magicjar.chart-curator.backfill.window-days:30}")
    private val windowDaysDefault: Int,
) {
    private val log = LoggerFactory.getLogger(CandleBackfillWorker::class.java)

    companion object {
        /** 햇징 룰 #1 가격 sanity 상한 — KOSPI 단위 보호 (1e9 = 10억 KRW) */
        val PRICE_MAX: BigDecimal = BigDecimal("1000000000")
        val PRICE_MIN: BigDecimal = BigDecimal.ZERO

        /** 햇징 룰 #8 — 재시도 backoff (ms): 즉시·1s·5s 3회 */
        val RETRY_BACKOFF_MS: List<Long> = listOf(0L, 1_000L, 5_000L)

        /** 전체 실패 KillSwitch 임계치 — 갭 100% 실패 시 발동 (자본 보호 우선) */
        const val KILLSWITCH_FAILURE_RATIO = 1.0

        /** KillSwitch system user id (Phase 2-A 1차 MVP — user별 KillSwitch는 ADR 0084 P0 baseline 정합) */
        const val SYSTEM_USER_ID = "system"

        /** thread pool — backfill submit 비동기 */
        private const val POOL_SIZE = 2

        /** sanity monotonic WARN 샘플링 cap (per batch) — 로그 폭주(2026-06-05 readiness P0-2 41만 줄) 방지 */
        const val MONOTONIC_WARN_SAMPLE_CAP = 20

        /** 호출자가 expected/dryRun 인자 명시 — 룰 #5 source-of-truth */
        private const val SOURCE_KIS_BACKFILL = "KIS-BACKFILL"
    }

    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            POOL_SIZE,
            ThreadFactory { r ->
                Thread(r, "candle-backfill-${threadCounter.incrementAndGet()}").also {
                    it.isDaemon = true
                }
            },
        )

    /**
     * 백필 결과 — 갭을 3분류로 명시 (2026-06-05 readiness P0-2):
     *   - [insertedCount]: 실제 DB에 적재된 봉 수 (KIS 반환 + sanity 통과 + gap 매칭 + saveAll 성공)
     *   - [uncoveredCount]: 거래 없는 분(illiquid) 또는 KIS 미보유 구간 — KIS가 정상 응답했으나 해당 expected ts를
     *     반환하지 않음. 실패 아님. "SUCCESS + insertedCount=0"이 설명되는 분류(전부 uncovered).
     *   - [failedGapsCount]: KIS 통신 실패(전건 미반환) 또는 saveAll 저장 오류 — 진짜 실패.
     *
     * gapCount = insertedCount + uncoveredCount + (failedGapsCount 중 매칭 대상) 관계는 근사이며
     * (KIS 1건이 여러 expected를 cover하거나 cap 적용 가능), 운영자 audit·응답에서 3분류를 명시 기록한다.
     */
    data class BackfillResult(
        val code: String,
        val interval: CandleInterval,
        val backfillBatchId: String,
        val dryRun: Boolean,
        val gapCount: Int,
        val gapsSample: List<Instant>,
        val insertedCount: Int,
        val uncoveredCount: Int,
        val failedGapsCount: Int,
        val durationMs: Long,
        val status: BackfillStatus,
    )

    enum class BackfillStatus {
        SUCCESS, // 모든 갭 성공
        SKIPPED, // 거래정지·정리매매·갭 0건·회복 불가 마킹(재시도 중단)·throttle skip
        PARTIAL_FAIL, // 일부 갭 실패 (warn + Slack notice)
        FAILED_KILLSWITCH, // 전체 갭 실패 (KillSwitch on)
        UNRECOVERABLE, // KIS 보관 한도 밖·연속 전건 미반환 → 영구 회복 불가 마킹 + 재시도 중단 (rate-limit safety #1·#4)
    }

    /**
     * 비동기 backfill 제출 — LastCandleAtMonitor 등 자동 진입점 용도.
     */
    fun submit(
        code: String,
        interval: CandleInterval = CandleInterval.ONE_DAY,
        reason: String = "auto",
    ) {
        // rate-limit safety #1·#4 — 회복 불가 영구 마킹된 (code, interval)은 KIS 재시도 0 (무한 재시도 루프 차단).
        // 매 부팅 반복 알림 회피: 마킹 시점 1회 terminal 알림 발화 후 본 path는 조용히 skip.
        if (backfillGapGuardStore.isUnrecoverable(code, interval)) {
            log.info(
                "CandleBackfillWorker submit skip — 회복 불가 마킹(재시도 중단): code={} interval={} reason={}",
                code,
                interval.code,
                reason,
            )
            reconciliationStateStore.markReady(code, interval, "backfill-unrecoverable-skip")
            return
        }
        reconciliationStateStore.markReconciling(code, interval, reason)
        executor.submit {
            try {
                // rate-limit safety #3 — 직렬화 + run 간 간격 (부팅 N종목 동시 폭주 차단).
                val result =
                    backfillThrottleGate.runThrottled(code) {
                        runBackfill(
                            code,
                            interval,
                            dryRun = dryRunPropertyDefault,
                            windowDays = windowDaysDefault,
                            reason = reason,
                        )
                    }
                if (result == null) {
                    // throttle permit timeout 또는 circuit OPEN → 다음 사이클로 미룸 (재시도 자체는 차단하지 않음)
                    reconciliationStateStore.markReady(code, interval, "backfill-throttle-skip")
                    return@submit
                }
                // circuit breaker outcome 보고 — 연속 rate-limit 압박 run N회면 circuit OPEN(다음 윈도 이월).
                backfillThrottleGate.recordRunOutcome(isRateLimitPressure(result))
                when (result.status) {
                    BackfillStatus.SUCCESS,
                    BackfillStatus.SKIPPED,
                    BackfillStatus.UNRECOVERABLE,
                    -> reconciliationStateStore.markReady(code, interval, "backfill-${result.status}")
                    BackfillStatus.PARTIAL_FAIL,
                    BackfillStatus.FAILED_KILLSWITCH,
                    -> reconciliationStateStore.markFailed(code, interval, "backfill-${result.status}")
                }
            } catch (t: Throwable) {
                reconciliationStateStore.markFailed(code, interval, "backfill-exception")
                log.warn(
                    "CandleBackfillWorker 비동기 실행 예외: code={} interval={} err={}",
                    code,
                    interval.code,
                    t.message,
                )
            }
        }
    }

    /**
     * run 1건이 KIS rate-limit 압박을 시사하는가 (circuit breaker 판정용 — 2026-06-05 readiness P0-1).
     *
     * KIS 전건 미반환(FAILED_KILLSWITCH·UNRECOVERABLE 중 total-miss 경로)은 throttle 거부·TPS 폭주의 직접 증상이므로
     * rate-limit 압박으로 본다. SUCCESS·PARTIAL_FAIL(일부라도 적재)·SKIPPED는 KIS가 응답했으므로 압박 아님(reset).
     */
    private fun isRateLimitPressure(result: BackfillResult): Boolean =
        when (result.status) {
            BackfillStatus.FAILED_KILLSWITCH,
            BackfillStatus.UNRECOVERABLE,
            -> result.insertedCount == 0 && result.uncoveredCount == 0
            else -> false
        }

    /**
     * throttle gate + circuit breaker를 통과하는 동기 backfill 실행 — admin endpoint 용도
     * (2026-06-05 readiness P0-1 — 운영자 우회 폐기, admin도 KIS 단일 bucket throttle 통과).
     *
     * @return [runBackfill] 결과, 또는 throttle permit timeout·circuit OPEN 시 null
     *   (호출자가 queued/skip 상태로 응답 — KIS 추가 호출 0).
     */
    fun runBackfillThrottled(
        code: String,
        interval: CandleInterval,
        dryRun: Boolean = dryRunPropertyDefault,
        windowDays: Int = windowDaysDefault,
        reason: String = "admin",
    ): BackfillResult? {
        val result =
            backfillThrottleGate.runThrottled(code) {
                runBackfill(code, interval, dryRun, windowDays, reason)
            }
        if (result != null) {
            backfillThrottleGate.recordRunOutcome(isRateLimitPressure(result))
        }
        return result
    }

    /**
     * 동기 backfill 실행 — admin endpoint 용도 (호출자 응답 대기).
     *
     * 흐름 (햇징 룰 10건 본문):
     *   1. backfill_batch_id 발행 + audit CANDLE_BACKFILL_START 발행 (룰 #3·#9)
     *   2. 거래정지·정리매매 skip (룰 #6) → audit CANDLE_BACKFILL_SKIPPED
     *   3. CandleGapDetector.detectGaps() (룰 #6·#7 가드 내장)
     *   4. dryRun=true → audit + 응답만 반환 (룰 #2)
     *   5. 각 갭별 KIS fetch + sanity check (룰 #1) + 3회 retry (룰 #8) + UPSERT (룰 #4)
     *   6. 결과 집계 → audit SUCCESS/PARTIAL_FAIL/FAIL (룰 #9) + KillSwitch (룰 #8)
     */
    fun runBackfill(
        code: String,
        interval: CandleInterval,
        dryRun: Boolean = dryRunPropertyDefault,
        windowDays: Int = windowDaysDefault,
        reason: String = "manual",
    ): BackfillResult {
        val startMs = System.currentTimeMillis()
        val backfillBatchId =
            "candle-backfill:$code:${interval.code}:" +
                "$startMs-${UUID.randomUUID()}"

        log.info(
            "CandleBackfillWorker start: code={} interval={} dryRun={} reason={} batchId={}",
            code,
            interval.code,
            dryRun,
            reason,
            backfillBatchId,
        )

        // 룰 #6 — 거래정지·정리매매 skip (룰 false positive 가드)
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        if (instrument != null && (instrument.tradingHalt || instrument.liquidation)) {
            persistAudit(
                action = "CANDLE_BACKFILL_SKIPPED",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "interval" to interval.code,
                        "reason" to "TRADING_HALT",
                        "tradingHalt" to instrument.tradingHalt,
                        "liquidation" to instrument.liquidation,
                    ),
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = dryRun,
                gapCount = 0,
                gapsSample = emptyList(),
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SKIPPED,
            )
        }

        // rate-limit safety #1·#4 — 회복 불가 영구 마킹 종목은 KIS fetch 0 (admin path 방어 포함).
        // submit()에서 이미 skip하지만, admin 동기 호출·재진입 시에도 무한 재시도 차단.
        if (backfillGapGuardStore.isUnrecoverable(code, interval)) {
            persistAudit(
                action = "CANDLE_BACKFILL_SKIPPED",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "interval" to interval.code,
                        "reason" to "UNRECOVERABLE_GAP",
                        "backfillBatchId" to backfillBatchId,
                    ),
            )
            log.info(
                "CandleBackfillWorker — 회복 불가 마킹 skip(재시도 중단): code={} interval={} batchId={}",
                code,
                interval.code,
                backfillBatchId,
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = dryRun,
                gapCount = 0,
                gapsSample = emptyList(),
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.UNRECOVERABLE,
            )
        }

        // CandleGapDetector — 룰 #6·#7 false positive·negative 가드 내장
        val detectedGaps =
            try {
                candleGapDetector.detectGaps(code, interval, windowDays)
            } catch (e: Exception) {
                log.warn(
                    "CandleBackfillWorker detectGaps 예외: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                emptyList()
            }

        // rate-limit safety #2 — 갭 상한 cap (1회 run당 최대 봉 수).
        // 초과 시 가장 최근 구간부터 cap만큼만 처리하고 나머지는 다음 사이클로 미룸 (KIS 폭주 차단).
        val maxGapsPerRun = chartCandleProperties.backfillGuard.maxGapsPerRun.coerceAtLeast(1)
        val gaps =
            if (detectedGaps.size > maxGapsPerRun) {
                // detectGaps는 ASC(과거→최근) 반환 → takeLast가 가장 최근 cap건
                val capped = detectedGaps.takeLast(maxGapsPerRun)
                log.warn(
                    "CandleBackfillWorker 갭 cap 적용: code={} interval={} detected={} cap={} 나머지={} (다음 사이클)",
                    code,
                    interval.code,
                    detectedGaps.size,
                    maxGapsPerRun,
                    detectedGaps.size - maxGapsPerRun,
                )
                capped
            } else {
                detectedGaps
            }

        // 룰 #3 — audit CANDLE_BACKFILL_START (gapCount·dryRun 포함 + detected/cap evidence — safety #2)
        persistAudit(
            action = "CANDLE_BACKFILL_START",
            entityId = code,
            detail =
                mapOf(
                    "code" to code,
                    "interval" to interval.code,
                    "backfillBatchId" to backfillBatchId,
                    "gapCount" to gaps.size,
                    "detectedGapCount" to detectedGaps.size,
                    "gapCapApplied" to (detectedGaps.size > maxGapsPerRun),
                    "dryRun" to dryRun,
                    "reason" to reason,
                ),
        )

        if (gaps.isEmpty()) {
            persistAudit(
                action = "CANDLE_BACKFILL_SKIPPED",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "reason" to "NO_GAPS",
                        "backfillBatchId" to backfillBatchId,
                    ),
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = dryRun,
                gapCount = 0,
                gapsSample = emptyList(),
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SKIPPED,
            )
        }

        val gapsSample = gaps.take(10) // 응답 한도 보호

        // 룰 #2 — dry-run 모드 (DB 적재 0)
        if (dryRun) {
            log.info(
                "CandleBackfillWorker DRY-RUN: code={} interval={} gapCount={} batchId={}",
                code,
                interval.code,
                gaps.size,
                backfillBatchId,
            )
            persistAudit(
                action = "CANDLE_BACKFILL_SUCCESS",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "backfillBatchId" to backfillBatchId,
                        "insertedCount" to 0,
                        "dryRun" to true,
                        "gapCount" to gaps.size,
                        "durationMs" to (System.currentTimeMillis() - startMs),
                    ),
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = true,
                gapCount = gaps.size,
                gapsSample = gapsSample,
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = 0,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.SUCCESS,
            )
        }

        // 실 backfill — 갭별 KIS fetch + sanity + retry + UPSERT
        var insertedTotal = 0
        var failedGapsCount = 0
        val failedGapsSample = mutableListOf<Instant>()

        // KIS fetch는 갭별이 아닌 전체 캔들 일괄 fetch + filter 방식이 효율
        // (Phase 1 cold-start 패턴 정합 — fetchCandles 전체 응답 후 gaps 매칭)
        // 사유: KIS endpoint는 페이지네이션 100건 한도 → 갭별 1:1 호출은 비효율
        val fetchCount =
            when (interval) {
                CandleInterval.ONE_DAY -> 200 // Phase 1 ColdStart baseline
                CandleInterval.ONE_MIN -> 1 // KisRestCandleAdapter 내부 30일 페이지네이션 (count 무시)
                else -> 100
            }

        val fetchedCandles: List<Candle> =
            try {
                fetchWithRetry(code, interval, fetchCount, backfillBatchId)
            } catch (e: Exception) {
                log.warn(
                    "CandleBackfillWorker KIS fetch 3회 실패: code={} interval={} err={}",
                    code,
                    interval.code,
                    e.message,
                )
                emptyList()
            }

        if (fetchedCandles.isEmpty()) {
            // KIS 전건 미반환(total-miss). rate-limit safety #1·#4:
            //   연속 total-miss가 임계치(consecutiveTotalMissThreshold) 도달 시 → 회복 불가 영구 마킹 +
            //   1회 terminal 알림 + UNRECOVERABLE 반환 (다음 부팅/사이클 재시도 0 — 무한 루프 차단).
            //   임계치 미만이면 기존 fatal 경로 보존 (일시 장애일 수 있으므로 다음 사이클 재시도 허용).
            failedGapsCount = gaps.size
            failedGapsSample.addAll(gapsSample)
            val shouldGiveUp = backfillGapGuardStore.recordTotalMissAndShouldGiveUp(code, interval)
            if (shouldGiveUp) {
                backfillGapGuardStore.markUnrecoverable(
                    code,
                    interval,
                    reason = "CONSECUTIVE_TOTAL_MISS:gaps=${gaps.size}",
                )
                handleUnrecoverable(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    gapsTotal = gaps.size,
                    gapsSample = gapsSample,
                    durationMs = System.currentTimeMillis() - startMs,
                    reason = "KIS 연속 전건 미반환 임계치 도달",
                )
                return BackfillResult(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    dryRun = false,
                    gapCount = gaps.size,
                    gapsSample = gapsSample,
                    insertedCount = 0,
                    uncoveredCount = 0,
                    failedGapsCount = failedGapsCount,
                    durationMs = System.currentTimeMillis() - startMs,
                    status = BackfillStatus.UNRECOVERABLE,
                )
            }
            // 임계치 미만 — 일시 장애 가능. 기존 fatal 경로 (KillSwitch + 1회 alert) 보존.
            handleFailure(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                gapsTotal = gaps.size,
                failedGapsCount = failedGapsCount,
                failedGapsSample = failedGapsSample,
                durationMs = System.currentTimeMillis() - startMs,
                fatal = true,
            )
            return BackfillResult(
                code = code,
                interval = interval,
                backfillBatchId = backfillBatchId,
                dryRun = false,
                gapCount = gaps.size,
                gapsSample = gapsSample,
                insertedCount = 0,
                uncoveredCount = 0,
                failedGapsCount = failedGapsCount,
                durationMs = System.currentTimeMillis() - startMs,
                status = BackfillStatus.FAILED_KILLSWITCH,
            )
        }

        // KIS가 캔들을 반환함 → total-miss 연속 카운터 reset (회복 신호 — false positive 영구 마킹 방지).
        backfillGapGuardStore.recordRecovery(code, interval)

        // 룰 #1 — 이중 검증 sanity check
        val sane = sanityCheck(fetchedCandles, backfillBatchId)
        val gapSet: Set<Instant> = HashSet(gaps.map { it })
        val matched =
            sane.filter { candle ->
                // candle.ts (kotlinx) → java.time.Instant
                val ts = Instant.ofEpochSecond(candle.ts.epochSeconds)
                ts in gapSet
            }

        if (matched.isEmpty()) {
            log.warn(
                "CandleBackfillWorker — KIS fetched but no gaps matched: code={} interval={} fetched={} gaps={}",
                code,
                interval.code,
                sane.size,
                gaps.size,
            )
        }

        // 룰 #4 — UPSERT (ON CONFLICT DO NOTHING — Idempotency LOCK)
        try {
            insertedTotal =
                candleRepository.saveAll(
                    matched.map { candle ->
                        // 룰 #5 — source-of-truth: KIS raw 그대로 + backfill_batch_id 추가 위해 source 필드 LOCK
                        // backfill_batch_id 컬럼 적재는 Phase 2-B (Flyway V39 add-only 상태) — 1차 MVP는 source 그대로
                        candle.copy(source = SOURCE_KIS_BACKFILL)
                    },
                )
        } catch (e: Exception) {
            log.warn(
                "CandleBackfillWorker saveAll 실패: code={} interval={} err={}",
                code,
                interval.code,
                e.message,
            )
            failedGapsCount = gaps.size
        }

        // rate-limit safety #1 핵심 fix — unmatched 갭 ≠ 실패 (failed=5008/5008 무한 루프 root cause).
        //
        // 배경(2026-06-04 evidence): 1m detectGaps는 영업일 expected 분봉 전수(390~720/일)를 enumerate.
        //   거래 없는 분(illiquid minute)은 KIS가 영원히 bar를 반환하지 않음 → 그 expected ts는 영구 unmatched.
        //   기존 로직은 모든 unmatched expected ts를 "실패"로 집계 → failureRatio=1.0 → FAILED_KILLSWITCH(fatal)
        //   → 매 부팅 재시도 → KIS TPS_EXCEEDED 폭주. 하지만 KIS가 정상 응답한 이상 이는 upstream 실패가 아님.
        //
        // 처리: KIS가 캔들을 반환(fetchedCandles 비어있지 않음)한 경우, unmatched 갭은 "uncovered"(거래 없는 분
        //   또는 KIS 미보유 구간)로 간주하고 fatal 집계에서 제외. saveAll 자체가 실패한 경우만 실패로 본다.
        //   갭은 mock 합성 없이 갭으로 남기되(feedback_no_mock_data.md) 재시도만 종결.
        val matchedTsSet: Set<Instant> = HashSet(matched.map { Instant.ofEpochSecond(it.ts.epochSeconds) })
        val uncoveredGaps = gaps.filterNot { it in matchedTsSet }
        val uncoveredCount = uncoveredGaps.size
        if (uncoveredGaps.isNotEmpty()) {
            log.info(
                "CandleBackfillWorker — KIS 응답 정상이나 일부 expected 미커버(거래 없는 분/미보유): " +
                    "code={} interval={} gaps={} matched={} uncovered={} (실패 아님, 갭은 갭으로 남김)",
                code,
                interval.code,
                gaps.size,
                matched.size,
                uncoveredGaps.size,
            )
        }
        // saveAll 예외 시에만 failedGapsCount가 gaps.size로 설정됨(위 catch). 그 외에는 uncovered를 실패로 보지 않음.
        if (failedGapsCount > 0) {
            uncoveredGaps.take(10 - failedGapsSample.size).forEach { failedGapsSample.add(it) }
        }

        val durationMs = System.currentTimeMillis() - startMs
        val failureRatio = if (gaps.isNotEmpty()) failedGapsCount.toDouble() / gaps.size.toDouble() else 0.0
        val status =
            when {
                // saveAll 자체가 전건 실패한 경우만 fatal (KIS 응답은 정상이었으므로 흔치 않음 — DB 결함 path)
                failureRatio >= KILLSWITCH_FAILURE_RATIO -> BackfillStatus.FAILED_KILLSWITCH
                failedGapsCount > 0 -> BackfillStatus.PARTIAL_FAIL
                else -> BackfillStatus.SUCCESS
            }

        when (status) {
            // 3분류 명시 (2026-06-05 readiness P0-2) — "SUCCESS + insertedCount=0"이 uncoveredCount로
            // 설명됨(거래 없는 분/미보유). failedGapsCount=0이 진짜 성공 신호.
            BackfillStatus.SUCCESS ->
                persistAudit(
                    action = "CANDLE_BACKFILL_SUCCESS",
                    entityId = code,
                    detail =
                        mapOf(
                            "code" to code,
                            "backfillBatchId" to backfillBatchId,
                            "insertedCount" to insertedTotal,
                            "uncoveredCount" to uncoveredCount,
                            "failedGapsCount" to failedGapsCount,
                            "gapCount" to gaps.size,
                            "durationMs" to durationMs,
                        ),
                )
            BackfillStatus.PARTIAL_FAIL ->
                handleFailure(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    gapsTotal = gaps.size,
                    failedGapsCount = failedGapsCount,
                    failedGapsSample = failedGapsSample,
                    durationMs = durationMs,
                    fatal = false,
                )
            BackfillStatus.FAILED_KILLSWITCH ->
                handleFailure(
                    code = code,
                    interval = interval,
                    backfillBatchId = backfillBatchId,
                    gapsTotal = gaps.size,
                    failedGapsCount = failedGapsCount,
                    failedGapsSample = failedGapsSample,
                    durationMs = durationMs,
                    fatal = true,
                )
            BackfillStatus.SKIPPED,
            BackfillStatus.UNRECOVERABLE,
            -> { /* unreachable in this branch — early return 처리됨 */ }
        }

        log.info(
            "CandleBackfillWorker done: code={} interval={} status={} gaps={} inserted={} uncovered={} failed={} durationMs={} batchId={}",
            code,
            interval.code,
            status,
            gaps.size,
            insertedTotal,
            uncoveredCount,
            failedGapsCount,
            durationMs,
            backfillBatchId,
        )

        if (interval == CandleInterval.ONE_MIN && insertedTotal > 0) {
            rebuildAggregatesAfterOneMinBackfill(code, backfillBatchId)
        }

        return BackfillResult(
            code = code,
            interval = interval,
            backfillBatchId = backfillBatchId,
            dryRun = false,
            gapCount = gaps.size,
            gapsSample = gapsSample,
            insertedCount = insertedTotal,
            uncoveredCount = uncoveredCount,
            failedGapsCount = failedGapsCount,
            durationMs = durationMs,
            status = status,
        )
    }

    private fun rebuildAggregatesAfterOneMinBackfill(
        code: String,
        backfillBatchId: String,
    ) {
        try {
            val result = aggregateBackfillRunner.backfill(code)
            persistAudit(
                action = "CANDLE_AGGREGATE_REBUILD_AFTER_1M_BACKFILL",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "backfillBatchId" to backfillBatchId,
                        "result" to result,
                    ),
            )
            log.info("1m backfill 후 합산봉 재생성 완료: code={} result={}", code, result)
        } catch (e: Exception) {
            persistAudit(
                action = "CANDLE_AGGREGATE_REBUILD_FAIL_AFTER_1M_BACKFILL",
                entityId = code,
                detail =
                    mapOf(
                        "code" to code,
                        "backfillBatchId" to backfillBatchId,
                        "error" to e.message,
                    ),
            )
            log.warn("1m backfill 후 합산봉 재생성 실패: code={} err={}", code, e.message)
        }
    }

    /**
     * 햇징 룰 #1 — 이중 검증 (Double Verification):
     *   - 가격 range: 0 < open·high·low·close ≤ 1e9
     *   - 거래량 비음수: volume >= 0
     *   - time monotonic: 응답 list 내 ts 오름차순
     *   - OHLC 정합성: high >= max(open, close) ∧ low <= min(open, close) ∧ low <= high
     *
     * 위반 시 적재 0 + warn log (audit는 호출자 집계 단계에서 발행).
     */
    fun sanityCheck(
        candles: List<Candle>,
        backfillBatchId: String,
    ): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val valid = mutableListOf<Candle>()
        var prevTsSec: Long = Long.MIN_VALUE
        var monotonicViolation = false
        // monotonic WARN 샘플링 cap — 동일 batch 내 최대 MONOTONIC_WARN_SAMPLE_CAP 줄까지만 per-row WARN.
        // 배경(2026-06-05 readiness P0-2): 어댑터 정규화 결함으로 41만 줄 로그 폭주 발생. 어댑터 ASC 정규화로
        // root cause는 제거했으나, 진짜 비단조 KIS 응답이 들어와도 로그 폭주는 재발하지 않도록 방어.
        var monotonicWarnCount = 0
        for ((idx, c) in candles.withIndex()) {
            // 가격 range
            if (c.openRaw <= PRICE_MIN ||
                c.openRaw > PRICE_MAX ||
                c.highRaw <= PRICE_MIN ||
                c.highRaw > PRICE_MAX ||
                c.lowRaw <= PRICE_MIN ||
                c.lowRaw > PRICE_MAX ||
                c.closeRaw <= PRICE_MIN ||
                c.closeRaw > PRICE_MAX
            ) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 가격 range: code={} ts={} O={} H={} L={} C={} batchId={}",
                    c.code,
                    c.ts,
                    c.openRaw,
                    c.highRaw,
                    c.lowRaw,
                    c.closeRaw,
                    backfillBatchId,
                )
                continue
            }
            // 거래량 비음수
            if (c.volume < 0) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 volume 음수: code={} ts={} volume={} batchId={}",
                    c.code,
                    c.ts,
                    c.volume,
                    backfillBatchId,
                )
                continue
            }
            // OHLC 정합성
            val maxOC = if (c.openRaw > c.closeRaw) c.openRaw else c.closeRaw
            val minOC = if (c.openRaw < c.closeRaw) c.openRaw else c.closeRaw
            if (c.highRaw < maxOC || c.lowRaw > minOC || c.lowRaw > c.highRaw) {
                log.warn(
                    "CandleBackfillWorker sanity 실패 OHLC: code={} ts={} O={} H={} L={} C={} batchId={}",
                    c.code,
                    c.ts,
                    c.openRaw,
                    c.highRaw,
                    c.lowRaw,
                    c.closeRaw,
                    backfillBatchId,
                )
                continue
            }
            // time monotonic — index 1 이상에서 prevTs와 비교 (등호 허용 X — 엄격)
            val tsSec = c.ts.epochSeconds
            if (idx > 0 && tsSec <= prevTsSec) {
                monotonicViolation = true
                monotonicWarnCount++
                // 위반 시 해당 candle skip (이전 candle은 이미 valid 추가됨 — 1차 MVP 보존)
                // 샘플링 cap 초과분은 debug로 강등 — 로그 폭주(41만 줄) 재발 방지.
                if (monotonicWarnCount <= MONOTONIC_WARN_SAMPLE_CAP) {
                    log.warn(
                        "CandleBackfillWorker sanity 실패 monotonic: code={} idx={} ts={} prev={} batchId={} (sample {}/{})",
                        c.code,
                        idx,
                        c.ts,
                        prevTsSec,
                        backfillBatchId,
                        monotonicWarnCount,
                        MONOTONIC_WARN_SAMPLE_CAP,
                    )
                } else {
                    log.debug(
                        "CandleBackfillWorker sanity monotonic 위반(샘플 cap 초과 — debug 강등): code={} idx={} batchId={}",
                        c.code,
                        idx,
                        backfillBatchId,
                    )
                }
                continue
            }
            prevTsSec = tsSec
            valid.add(c)
        }
        if (monotonicViolation) {
            // 어댑터 ASC 정규화(KisRestCandleAdapter) 이후에도 위반이 남으면 진짜 비단조 KIS 응답 — 1회 집계 WARN.
            log.warn(
                "CandleBackfillWorker — monotonic violation 감지 (어댑터 정규화 후에도 비단조 — 진짜 KIS 응답 결함): " +
                    "total={} valid={} violations={} batchId={}",
                candles.size,
                valid.size,
                monotonicWarnCount,
                backfillBatchId,
            )
        }
        return valid
    }

    /**
     * 햇징 룰 #8 — 3회 backoff (즉시·1s·5s) KIS fetch retry.
     * 3회 모두 실패 시 IllegalStateException throw — 호출자가 KillSwitch 발동 판단.
     */
    private fun fetchWithRetry(
        code: String,
        interval: CandleInterval,
        count: Int,
        backfillBatchId: String,
    ): List<Candle> {
        var lastErr: Throwable? = null
        for ((attempt, backoffMs) in RETRY_BACKOFF_MS.withIndex()) {
            if (backoffMs > 0) {
                try {
                    Thread.sleep(backoffMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("CandleBackfillWorker interrupted", e)
                }
            }
            try {
                val candles = kisRestPort.fetchCandles(code, interval, count)
                if (candles.isNotEmpty()) {
                    log.debug(
                        "CandleBackfillWorker fetch 성공: code={} interval={} attempt={} count={} batchId={}",
                        code,
                        interval.code,
                        attempt + 1,
                        candles.size,
                        backfillBatchId,
                    )
                    return candles
                }
                log.warn(
                    "CandleBackfillWorker fetch 빈 응답 attempt={}: code={} interval={} batchId={}",
                    attempt + 1,
                    code,
                    interval.code,
                    backfillBatchId,
                )
            } catch (e: Exception) {
                lastErr = e
                log.warn(
                    "CandleBackfillWorker fetch 실패 attempt={}: code={} interval={} err={} batchId={}",
                    attempt + 1,
                    code,
                    interval.code,
                    e.message,
                    backfillBatchId,
                )
            }
        }
        throw IllegalStateException(
            "CandleBackfillWorker 3회 retry 실패: code=$code interval=${interval.code} batchId=$backfillBatchId",
            lastErr,
        )
    }

    /**
     * rate-limit safety #1·#4 — 회복 불가 영구 마킹 terminal 알림 (1회만, 매 부팅 반복 알림 차단).
     *
     * KillSwitch는 발동하지 않는다 — 회복 불가 갭은 자본 결함이 아니라 KIS 보관 한도/거래 부재로 인한
     * 정상 데이터 부재이며, 갭은 mock 합성 없이 갭으로 남긴다(feedback_no_mock_data.md). 재시도만 종결.
     */
    private fun handleUnrecoverable(
        code: String,
        interval: CandleInterval,
        backfillBatchId: String,
        gapsTotal: Int,
        gapsSample: List<Instant>,
        durationMs: Long,
        reason: String,
    ) {
        val detail =
            mapOf(
                "code" to code,
                "interval" to interval.code,
                "backfillBatchId" to backfillBatchId,
                "gapsTotal" to gapsTotal,
                "gapsSample" to gapsSample.take(5).map { it.toString() },
                "durationMs" to durationMs,
                "reason" to reason,
                "retryStopped" to true,
            )
        persistAudit(action = "CANDLE_BACKFILL_UNRECOVERABLE", entityId = code, detail = detail)

        val sender = slackSenderProvider.getIfAvailable()
        if (sender != null) {
            val msg =
                "[magicJar] CandleBackfill 회복 불가 — 재시도 중단(terminal) — code=$code interval=${interval.code} " +
                    "gaps=$gapsTotal reason=$reason. KIS 보관 한도(최대 1년)/거래 없는 분 구간으로 추정. " +
                    "갭은 그대로 남기고 자동 재시도를 종결합니다. batchId=$backfillBatchId"
            try {
                sender.sendDirect(msg)
            } catch (e: Exception) {
                log.warn("CandleBackfillWorker UNRECOVERABLE Slack 발화 실패: err={}", e.message)
            }
        }
        log.warn(
            "CandleBackfillWorker UNRECOVERABLE terminal — 재시도 중단: code={} interval={} gaps={} reason={} batchId={}",
            code,
            interval.code,
            gapsTotal,
            reason,
            backfillBatchId,
        )
    }

    /**
     * 햇징 룰 #8·#9 — 실패 audit + Slack notice + KillSwitch (전체 실패 시).
     */
    private fun handleFailure(
        code: String,
        interval: CandleInterval,
        backfillBatchId: String,
        gapsTotal: Int,
        failedGapsCount: Int,
        failedGapsSample: List<Instant>,
        durationMs: Long,
        fatal: Boolean,
    ) {
        val detail =
            mapOf(
                "code" to code,
                "interval" to interval.code,
                "backfillBatchId" to backfillBatchId,
                "gapsTotal" to gapsTotal,
                "failedGapsCount" to failedGapsCount,
                "failedGapsSample" to failedGapsSample.take(5).map { it.toString() },
                "durationMs" to durationMs,
                "fatal" to fatal,
            )
        persistAudit(action = "CANDLE_BACKFILL_FAIL", entityId = code, detail = detail)

        // Slack notice
        val sender = slackSenderProvider.getIfAvailable()
        if (sender != null) {
            val msg =
                "[magicJar] CandleBackfill 실패 — code=$code interval=${interval.code} " +
                    "failed=$failedGapsCount/$gapsTotal fatal=$fatal batchId=$backfillBatchId"
            try {
                sender.sendDirect(msg)
            } catch (e: Exception) {
                log.warn("CandleBackfillWorker Slack 발화 실패: err={}", e.message)
            }
        }

        // KillSwitch — 전체 실패(fatal=true)만 발동 (자본 보호 우선)
        // 햇징 룰 §8 hotfix (2026-05-19): user_account 존재 검증 의무 — system userId가 user_account에 없으면
        //   FK 위반 회피 + audit_trail SKIPPED 분기 + Slack notice만 발화 (자본 단위 KillSwitch는 자본 path 영역 T-RISK·T-O).
        if (fatal) {
            val ksRepo = killSwitchJpaRepositoryProvider.getIfAvailable()
            if (ksRepo != null) {
                triggerKillSwitchSafely(
                    ksRepo = ksRepo,
                    userId = SYSTEM_USER_ID,
                    reasonStr = "CANDLE_BACKFILL_FAIL:$code:${interval.code}",
                    backfillBatchId = backfillBatchId,
                    sender = sender,
                )
            }
        }
    }

    /**
     * 햇징 룰 §8 hotfix — KillSwitch enable 안전망.
     *
     * 사유: `kill_switch_state.user_id`는 `user_account(user_id)` FK (V1__baseline.sql §9 ON DELETE CASCADE).
     * system userId("system")가 user_account에 없으면 INSERT 시 FK 위반 → audit_trail FAIL + 운영 부채.
     *
     * 처리:
     *   1. existsUserAccount() 사전 검증 → 없으면 KillSwitch INSERT skip + warn + Slack
     *   2. audit_trail에 `CANDLE_BACKFILL_KILLSWITCH_SKIPPED` 발행 (운영자 인지 path)
     *   3. 실 enable 호출 시도 + 예외 발생 시 graceful (예: FK 외 다른 결함)
     *
     * 자본 영향 0: 캔들 정합성 KillSwitch는 user 단위 자본 path와 분리 — Slack notice + audit만으로 운영자 인지.
     */
    private fun triggerKillSwitchSafely(
        ksRepo: KillSwitchJpaRepository,
        userId: String,
        reasonStr: String,
        backfillBatchId: String,
        sender: SlackWebhookSender?,
    ) {
        val userExists =
            try {
                ksRepo.existsUserAccount(userId)
            } catch (e: Exception) {
                log.warn(
                    "CandleBackfillWorker existsUserAccount 검증 실패 → skip 분기: userId={} err={} batchId={}",
                    userId,
                    e.message,
                    backfillBatchId,
                )
                false
            }
        if (!userExists) {
            log.warn(
                "CandleBackfillWorker — KillSwitch SKIPPED (user_account 부재): userId={} reason={} batchId={}",
                userId,
                reasonStr,
                backfillBatchId,
            )
            persistAudit(
                action = "CANDLE_BACKFILL_KILLSWITCH_SKIPPED",
                entityId = userId,
                detail =
                    mapOf(
                        "userId" to userId,
                        "reason" to reasonStr,
                        "backfillBatchId" to backfillBatchId,
                        "skipReason" to "USER_ACCOUNT_NOT_FOUND",
                    ),
            )
            // 운영자 인지용 Slack notice
            if (sender != null) {
                try {
                    sender.sendDirect(
                        "[magicJar] CandleBackfill KillSwitch SKIPPED — user_account 부재(userId=$userId). " +
                            "캔들 정합성 KillSwitch는 자본 path T-RISK·T-O 영역이며 chart-curator는 audit·Slack notice만 발행. " +
                            "reason=$reasonStr batchId=$backfillBatchId",
                    )
                } catch (e: Exception) {
                    log.warn("CandleBackfillWorker KillSwitch SKIPPED Slack 발화 실패: err={}", e.message)
                }
            }
            return
        }
        try {
            ksRepo.enableKillSwitch(
                userId = userId,
                reason = reasonStr,
                updatedBy = "chart-curator",
            )
            log.warn(
                "CandleBackfillWorker — KillSwitch ON: userId={} reason={} batchId={}",
                userId,
                reasonStr,
                backfillBatchId,
            )
        } catch (e: Exception) {
            log.warn(
                "CandleBackfillWorker KillSwitch enable 실패: err={} batchId={}",
                e.message,
                backfillBatchId,
            )
        }
    }

    /**
     * 햇징 룰 #9 — audit_trail row 1건 발행.
     * action: CANDLE_BACKFILL_START / SUCCESS / FAIL / SKIPPED / ROLLBACK 5 path
     *         + KILLSWITCH_SKIPPED (햇징 룰 §8 hotfix — user_account 부재 분기)
     *
     * detail Map은 ObjectMapper.writeValueAsString()로 valid JSON 변환 후 영속 (audit_trail.detail_json JSONB 정합).
     * 사유: `@JdbcTypeCode(SqlTypes.JSON)` + Hibernate 6은 Kotlin String을 raw 전달 시
     * PostgreSQL JSONB validation 통과 못 함 (v3.6.17 dogfooding evidence —
     *   `ERROR: invalid input syntax for type json`). Map 직렬화로 안전 보장.
     */
    private fun persistAudit(
        action: String,
        entityId: String,
        detail: Map<String, Any?>,
    ) {
        val detailJson =
            try {
                objectMapper.writeValueAsString(detail)
            } catch (e: Exception) {
                log.warn("CandleBackfillWorker audit JSON 직렬화 실패: action={} err={}", action, e.message)
                // 빈 JSON object로 graceful fallback — audit row 자체는 발행
                "{}"
            }
        try {
            auditRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "chart-curator",
                    action = action,
                    entityType = "candle",
                    entityId = entityId,
                    detailJson = detailJson,
                ),
            )
        } catch (e: Exception) {
            log.warn("CandleBackfillWorker audit 저장 실패: action={} err={}", action, e.message)
        }
    }

    /**
     * 햇징 룰 #10 — 운영자 수동 rollback (audit only).
     * 실제 DELETE는 admin SQL 또는 향후 admin endpoint 확장 (1차 MVP는 audit row만).
     */
    fun recordRollback(
        backfillBatchId: String,
        deletedCount: Int,
        reason: String,
    ) {
        persistAudit(
            action = "CANDLE_BACKFILL_ROLLBACK",
            entityId = backfillBatchId,
            detail =
                mapOf(
                    "backfillBatchId" to backfillBatchId,
                    "deletedCount" to deletedCount,
                    "reason" to reason,
                ),
        )
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
