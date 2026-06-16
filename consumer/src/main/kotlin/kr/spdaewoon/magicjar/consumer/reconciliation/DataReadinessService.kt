package kr.spdaewoon.magicjar.consumer.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlinx.datetime.Instant as KtInstant

enum class DataReadinessStatus {
    READY,
    RECONCILING,
    STALE,
    FAILED,
    UNRECOVERABLE,
}

data class DataReadinessResult(
    val status: DataReadinessStatus,
    val reason: String,
    val code: String,
    val interval: CandleInterval,
    val boundaryTs: KtInstant,
    val latestIntervalTs: Instant?,
    val latestOneMinTs: Instant?,
) {
    val ready: Boolean
        get() = status == DataReadinessStatus.READY
}

/**
 * Data readiness gate for realtime strategy evaluation.
 *
 * If candle reconciliation is running, has failed, or the DB latest candle is behind the boundary being
 * evaluated, the strategy path must skip before Signal is created. That keeps autotrading fail-closed.
 */
@Component
class DataReadinessService(
    private val candleRepository: CandleRepository,
    private val stateStore: CandleReconciliationStateStore,
    private val auditTrailJpaRepository: ConsumerAuditTrailJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(DataReadinessService::class.java)

    fun check(
        code: String,
        interval: CandleInterval,
        boundaryTs: KtInstant,
    ): DataReadinessResult {
        val state = stateStore.stateOf(code, CandleInterval.ONE_MIN) ?: stateStore.stateOf(code, interval)
        if (state?.status == CandleReconciliationStateStore.Status.RECONCILING) {
            return result(
                status = DataReadinessStatus.RECONCILING,
                reason = state.reason,
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
            )
        }
        if (state?.status == CandleReconciliationStateStore.Status.FAILED) {
            return result(
                status = DataReadinessStatus.FAILED,
                reason = state.reason,
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
            )
        }

        val boundaryJava = boundaryTs.toJavaInstant()
        val latestIntervalTs =
            try {
                candleRepository.findMaxTsByCodeAndInterval(code, interval)
            } catch (e: Exception) {
                log.warn("data readiness interval maxTs 조회 실패: code={} interval={} err={}", code, interval.code, e.message)
                return result(DataReadinessStatus.FAILED, "interval-max-ts-query-failed", code, interval, boundaryTs)
            }
        if (latestIntervalTs == null) {
            return result(DataReadinessStatus.STALE, "interval-candle-missing", code, interval, boundaryTs)
        }
        if (latestIntervalTs.isBefore(boundaryJava)) {
            return result(
                status = DataReadinessStatus.STALE,
                reason = "interval-candle-behind-boundary",
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
                latestIntervalTs = latestIntervalTs,
            )
        }

        val latestOneMinTs =
            if (interval.isIntraday()) {
                try {
                    candleRepository.findMaxTsByCodeAndInterval(code, CandleInterval.ONE_MIN)
                } catch (e: Exception) {
                    log.warn("data readiness 1m maxTs 조회 실패: code={} err={}", code, e.message)
                    return result(DataReadinessStatus.FAILED, "one-min-max-ts-query-failed", code, interval, boundaryTs)
                }
            } else {
                null
            }

        if (interval.isIntraday() && latestOneMinTs == null) {
            return result(
                status = DataReadinessStatus.STALE,
                reason = "one-min-candle-missing",
                code = code,
                interval = interval,
                boundaryTs = boundaryTs,
                latestIntervalTs = latestIntervalTs,
            )
        }

        if (latestOneMinTs != null) {
            val toleratedLag = Duration.ofMinutes(interval.minutes().coerceAtLeast(1L) + 2L)
            if (latestOneMinTs.isBefore(boundaryJava.minus(toleratedLag))) {
                return result(
                    status = DataReadinessStatus.STALE,
                    reason = "one-min-candle-stale",
                    code = code,
                    interval = interval,
                    boundaryTs = boundaryTs,
                    latestIntervalTs = latestIntervalTs,
                    latestOneMinTs = latestOneMinTs,
                )
            }
        }

        return result(
            status = DataReadinessStatus.READY,
            reason = "ready",
            code = code,
            interval = interval,
            boundaryTs = boundaryTs,
            latestIntervalTs = latestIntervalTs,
            latestOneMinTs = latestOneMinTs,
        )
    }

    fun auditSkip(
        result: DataReadinessResult,
        strategyIds: List<Long>,
    ) {
        try {
            auditTrailJpaRepository.save(
                ConsumerAuditTrailJpaEntity(
                    actor = "system",
                    action = "SIGNAL_SKIPPED_DATA_NOT_READY",
                    entityType = "signal",
                    entityId = result.code,
                    detailJson =
                        objectMapper.writeValueAsString(
                            mapOf(
                                "code" to result.code,
                                "interval" to result.interval.code,
                                "boundaryTs" to result.boundaryTs.toString(),
                                "status" to result.status.name,
                                "reason" to result.reason,
                                "latestIntervalTs" to result.latestIntervalTs?.toString(),
                                "latestOneMinTs" to result.latestOneMinTs?.toString(),
                                "strategyIds" to strategyIds,
                            ),
                        ),
                ),
            )
        } catch (e: Exception) {
            log.warn("SIGNAL_SKIPPED_DATA_NOT_READY audit 저장 실패: code={} err={}", result.code, e.message)
        }
    }

    private fun result(
        status: DataReadinessStatus,
        reason: String,
        code: String,
        interval: CandleInterval,
        boundaryTs: KtInstant,
        latestIntervalTs: Instant? = null,
        latestOneMinTs: Instant? = null,
    ): DataReadinessResult =
        DataReadinessResult(
            status = status,
            reason = reason,
            code = code,
            interval = interval,
            boundaryTs = boundaryTs,
            latestIntervalTs = latestIntervalTs,
            latestOneMinTs = latestOneMinTs,
        )

    private fun KtInstant.toJavaInstant(): Instant = Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    private fun CandleInterval.isIntraday(): Boolean =
        when (this) {
            CandleInterval.ONE_MIN,
            CandleInterval.TWO_MIN,
            CandleInterval.THREE_MIN,
            CandleInterval.FIVE_MIN,
            CandleInterval.FIFTEEN_MIN,
            CandleInterval.THIRTY_MIN,
            CandleInterval.NINETY_MIN,
            CandleInterval.ONE_HOUR,
            CandleInterval.FOUR_HOUR,
            CandleInterval.TWELVE_HOUR,
            -> true
            CandleInterval.ONE_DAY,
            CandleInterval.ONE_WEEK,
            CandleInterval.ONE_MONTH,
            CandleInterval.ONE_YEAR,
            -> false
        }

    private fun CandleInterval.minutes(): Long =
        when (this) {
            CandleInterval.ONE_MIN -> 1L
            CandleInterval.TWO_MIN -> 2L
            CandleInterval.THREE_MIN -> 3L
            CandleInterval.FIVE_MIN -> 5L
            CandleInterval.FIFTEEN_MIN -> 15L
            CandleInterval.THIRTY_MIN -> 30L
            CandleInterval.NINETY_MIN -> 90L
            CandleInterval.ONE_HOUR -> 60L
            CandleInterval.FOUR_HOUR -> 240L
            CandleInterval.TWELVE_HOUR -> 720L
            CandleInterval.ONE_DAY -> 1_440L
            CandleInterval.ONE_WEEK -> 10_080L
            CandleInterval.ONE_MONTH -> 43_200L
            CandleInterval.ONE_YEAR -> 525_600L
        }
}
