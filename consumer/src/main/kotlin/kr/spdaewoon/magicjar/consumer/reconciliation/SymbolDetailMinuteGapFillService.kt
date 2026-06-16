package kr.spdaewoon.magicjar.consumer.reconciliation

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.consumer.aggregation.AggregateBackfillRunner
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.port.KisOneMinuteCandleRangePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * SymbolDetail ENTER path minute gap-fill enqueue.
 *
 * 상세 화면 진입은 KIS WS current candle을 즉시 요청하고, DB 1m이 마지막 닫힌 분봉보다 뒤처졌을 때만
 * REST backfill을 비동기로 붙인다. Listener ack와 사용자의 화면 진입은 backfill 완료를 기다리지 않는다.
 */
@Component
class SymbolDetailMinuteGapFillService(
    private val candleRepository: CandleRepository,
    private val kisOneMinuteCandleRangePort: KisOneMinuteCandleRangePort,
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val reconciliationStateStore: CandleReconciliationStateStore,
) {
    private val log = LoggerFactory.getLogger(SymbolDetailMinuteGapFillService::class.java)
    private val inFlightUntilMs = ConcurrentHashMap<String, Long>()
    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(
            2,
            ThreadFactory { r ->
                Thread(r, "symbol-detail-1m-gapfill-${threadCounter.incrementAndGet()}").also {
                    it.isDaemon = true
                }
            },
        )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val SESSION_OPEN = java.time.LocalTime.of(8, 0)
        private const val DEDUPE_MS = 60_000L
        private const val SOURCE_KIS_RANGE_GAPFILL = "KIS-RANGE-GAPFILL"
    }

    fun submitIfNeeded(
        code: String,
        reason: String = "symbol-detail-enter",
    ) {
        val targetTo = latestClosedMinute()
        val lastTs =
            try {
                candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN)
            } catch (e: Exception) {
                log.warn("SymbolDetail 1m maxTs 조회 실패 — backfill 큐잉: code={} err={}", code, e.message)
                null
            }

        val targetFrom =
            lastTs
                ?.plusSeconds(60)
                ?: sessionOpenFor(targetTo)

        if (targetFrom.isAfter(targetTo)) {
            log.debug("SymbolDetail 1m gap fill 범위 없음: code={} targetFrom={} targetTo={}", code, targetFrom, targetTo)
            return
        }

        if (lastTs != null && !lastTs.isBefore(targetTo)) {
            log.debug("SymbolDetail 1m gap fill 불필요: code={} lastTs={} targetTo={}", code, lastTs, targetTo)
            return
        }

        val now = System.currentTimeMillis()
        val inFlightUntil = inFlightUntilMs[code]
        if (inFlightUntil != null && now < inFlightUntil) {
            log.debug("SymbolDetail 1m gap fill dedupe: code={} until={}", code, inFlightUntil)
            return
        }

        inFlightUntilMs[code] = now + DEDUPE_MS
        reconciliationStateStore.markReconciling(code, CandleInterval.ONE_MIN, reason)
        executor.submit {
            runRangeFill(code = code, targetFrom = targetFrom, targetTo = targetTo, reason = reason)
        }
        log.info(
            "SymbolDetail 1m range gap fill 큐잉: code={} lastTs={} targetFrom={} targetTo={}",
            code,
            lastTs,
            targetFrom,
            targetTo,
        )
    }

    fun runRangeFill(
        code: String,
        targetFrom: Instant,
        targetTo: Instant,
        reason: String = "symbol-detail-enter",
    ) {
        try {
            val fetched = kisOneMinuteCandleRangePort.fetchOneMinuteCandlesBetween(code, targetFrom, targetTo)
            val inserted =
                candleRepository.saveAll(
                    fetched.map { candle ->
                        candle.copy(source = SOURCE_KIS_RANGE_GAPFILL)
                    },
                )
            if (inserted > 0) {
                val aggregateResult = aggregateBackfillRunner.backfill(code)
                val failedAggregates = aggregateResult.filterValues { it < 0 }
                if (failedAggregates.isNotEmpty()) {
                    throw IllegalStateException("aggregate rebuild failed: $failedAggregates")
                }
                log.info(
                    "SymbolDetail 1m range gap fill 완료 + 합산봉 재생성: code={} range={}..{} fetched={} inserted={} aggregate={}",
                    code,
                    targetFrom,
                    targetTo,
                    fetched.size,
                    inserted,
                    aggregateResult,
                )
            } else {
                log.info(
                    "SymbolDetail 1m range gap fill 완료: code={} range={}..{} fetched={} inserted=0 reason={}",
                    code,
                    targetFrom,
                    targetTo,
                    fetched.size,
                    reason,
                )
            }
            reconciliationStateStore.markReady(code, CandleInterval.ONE_MIN, "range-gapfill-completed")
        } catch (e: Exception) {
            reconciliationStateStore.markFailed(code, CandleInterval.ONE_MIN, "range-gapfill-failed")
            log.warn(
                "SymbolDetail 1m range gap fill 실패: code={} range={}..{} reason={} err={}",
                code,
                targetFrom,
                targetTo,
                reason,
                e.message,
            )
        }
    }

    private fun latestClosedMinute(): Instant =
        ZonedDateTime
            .now(KST)
            .truncatedTo(ChronoUnit.MINUTES)
            .minusMinutes(1)
            .toInstant()

    private fun sessionOpenFor(targetTo: Instant): Instant =
        targetTo
            .atZone(KST)
            .toLocalDate()
            .atTime(SESSION_OPEN)
            .atZone(KST)
            .toInstant()

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }
}
