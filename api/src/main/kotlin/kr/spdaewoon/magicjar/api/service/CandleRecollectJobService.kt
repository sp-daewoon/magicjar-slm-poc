package kr.spdaewoon.magicjar.api.service

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.api.messaging.StompDestinations
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectJobResponse
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectProgressEvent
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectRangeRequest
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshProgressUpdate
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 사용자 수동 캔들 재수집 async job runner.
 *
 * 기존 동기 endpoint는 호환성을 위해 유지하고, Symbol Chart UX는 이 job 경로를 사용해
 * HTTP 대기 대신 STOMP progress + REST poll fallback으로 상태를 확인한다.
 */
@Service
class CandleRecollectJobService(
    private val candleRefreshService: CandleRefreshService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(CandleRecollectJobService::class.java)
    private val threadSeq = AtomicInteger(0)
    private val executor =
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "candle-recollect-job-${threadSeq.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    private val jobs = ConcurrentHashMap<String, CandleRecollectProgressEvent>()

    fun submitRecollect(
        code: String,
        intervals: List<String>,
        rangeRequest: CandleRecollectRangeRequest? = null,
        minuteRangeRequest: CandleRecollectRangeRequest? = null,
    ): CandleRecollectJobResponse {
        val jobId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val initial =
            CandleRecollectProgressEvent(
                jobId = jobId,
                code = code,
                intervals = intervals,
                status = STATUS_QUEUED,
                stage = "QUEUED",
                processedDays = null,
                totalDays = null,
                deletedCount = 0,
                insertedCount = 0,
                rebuiltCount = 0,
                message = "캔들 재수집 대기 중",
                elapsedMs = null,
                error = null,
                updatedAt = now,
            )
        publish(initial)

        executor.submit {
            runRecollectJob(initial, rangeRequest, minuteRangeRequest)
        }

        return CandleRecollectJobResponse(
            jobId = jobId,
            code = code,
            intervals = intervals,
            status = STATUS_QUEUED,
            topic = topicFor(code, jobId),
            submittedAt = now,
        )
    }

    fun find(
        code: String,
        jobId: String,
    ): CandleRecollectProgressEvent? = jobs[jobId]?.takeIf { it.code == code }

    private fun runRecollectJob(
        initial: CandleRecollectProgressEvent,
        rangeRequest: CandleRecollectRangeRequest?,
        minuteRangeRequest: CandleRecollectRangeRequest? = null,
    ) {
        val startedAt = System.currentTimeMillis()
        var latest =
            initial.copy(
                status = STATUS_RUNNING,
                stage = "RUNNING",
                message = "캔들 재수집 시작",
                updatedAt = Instant.now().toString(),
            )
        publish(latest)

        try {
            val response =
                candleRefreshService.recollect(
                    code = initial.code,
                    intervals = initial.intervals,
                    priority = KisCallPriority.USER_PATH,
                    rangeRequest = rangeRequest,
                    minuteRangeRequest = minuteRangeRequest,
                ) { update ->
                    latest = latest.merge(update)
                    publish(latest)
                }
            val inserted = response.refreshedIntervals.sumOf { it.insertedCount }
            val rebuilt = response.rebuiltIntervals.sumOf { it.insertedCount }
            publish(
                latest.copy(
                    status = STATUS_SUCCEEDED,
                    stage = "SUCCEEDED",
                    deletedCount = response.deletedCount,
                    insertedCount = inserted,
                    rebuiltCount = rebuilt,
                    message = "캔들 재수집 완료",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    error = null,
                    updatedAt = Instant.now().toString(),
                ),
            )
        } catch (e: Exception) {
            log.warn("캔들 재수집 job 실패: jobId={} code={} err={}", initial.jobId, initial.code, e.message)
            publish(
                latest.copy(
                    status = STATUS_FAILED,
                    stage = "FAILED",
                    message = "캔들 재수집 실패",
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    error = e.message ?: e.javaClass.simpleName,
                    updatedAt = Instant.now().toString(),
                ),
            )
        }
    }

    private fun CandleRecollectProgressEvent.merge(update: CandleRefreshProgressUpdate): CandleRecollectProgressEvent {
        val fetchedInsertedDelta =
            if (update.stage == "FETCHED") {
                update.insertedCount ?: 0
            } else {
                0
            }
        val rebuiltDelta =
            if (update.stage == "REBUILT") {
                update.insertedCount ?: 0
            } else {
                0
            }
        return copy(
            status = STATUS_RUNNING,
            stage = update.stage,
            processedDays = update.processedDays ?: processedDays,
            totalDays = update.totalDays ?: totalDays,
            insertedCount = insertedCount + fetchedInsertedDelta,
            rebuiltCount = rebuiltCount + rebuiltDelta,
            message = update.message,
            updatedAt = Instant.now().toString(),
        )
    }

    private fun publish(event: CandleRecollectProgressEvent) {
        jobs[event.jobId] = event
        try {
            messagingTemplate.convertAndSend(topicFor(event.code, event.jobId), event)
        } catch (e: Exception) {
            log.debug("캔들 재수집 progress STOMP 발행 실패: jobId={} err={}", event.jobId, e.message)
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"

        fun topicFor(
            code: String,
            jobId: String,
        ): String = StompDestinations.candleRecollectJob(code, jobId)
    }
}
