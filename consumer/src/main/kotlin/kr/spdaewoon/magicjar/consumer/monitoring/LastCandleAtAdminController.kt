package kr.spdaewoon.magicjar.consumer.monitoring

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `last_candle_at` 모니터 수동 트리거 admin REST endpoint (T-CC Phase 1).
 *
 * consumer 모듈 내부 REST (port 8082). 운영 편의 — dogfooding cycle 즉시 발화.
 * dispatch 명시 evidence — curl 검증 path 제공.
 *
 * endpoint:
 *   POST /admin/monitor/last-candle-at  — 전 watchlist stale 검사 + cold-start 자동 트리거
 *
 * 자본 영향 0 — 데이터 정합 보강만 수행.
 */
@RestController
@RequestMapping("/admin/monitor")
class LastCandleAtAdminController(
    private val lastCandleAtMonitor: LastCandleAtMonitor,
) {
    private val log = LoggerFactory.getLogger(LastCandleAtAdminController::class.java)

    @PostMapping("/last-candle-at")
    fun triggerMonitor(): ResponseEntity<MonitorResponse> {
        log.info("admin last-candle-at monitor trigger")
        val stale = lastCandleAtMonitor.runMonitor("admin")
        return ResponseEntity.ok(
            MonitorResponse(
                staleCount = stale.size,
                triggeredCodes = stale.map { it.code }.distinct(),
                detail =
                    stale.map { e ->
                        StaleEntryDto(
                            code = e.code,
                            interval = e.interval.code,
                            lastTs = e.lastTs?.toString(),
                            staleBusinessDays =
                                if (e.staleBusinessDays == Long.MAX_VALUE) {
                                    -1L
                                } else {
                                    e.staleBusinessDays
                                },
                        )
                    },
            ),
        )
    }

    data class MonitorResponse(
        val staleCount: Int,
        val triggeredCodes: List<String>,
        val detail: List<StaleEntryDto>,
    )

    data class StaleEntryDto(
        val code: String,
        val interval: String,
        val lastTs: String?,
        /** -1 = row 0건 (lastTs null), 이외는 거래일 단위 stale 일수 */
        val staleBusinessDays: Long,
    )
}
