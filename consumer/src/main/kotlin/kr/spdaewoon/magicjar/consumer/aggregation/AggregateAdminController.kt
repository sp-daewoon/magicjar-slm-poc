package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 집계(aggregation) 수동 트리거 admin REST endpoint.
 *
 * consumer 모듈 내부 REST (port 8082). 운영 편의 — DB 에 1m 이미 적재된 상태에서
 * 3m·5m·15m·30m·90m·1h·4h·12h 합산 재실행 시 사용.
 *
 * TradingView 정합 — 1m 베이스가 충분할 때 KRX 정규장 기준 합산만 재실행.
 *
 * endpoint:
 *   POST /admin/aggregate/{code}  — 단일 종목 합산
 *   POST /admin/aggregate/all     — watchlist 전 종목 합산
 */
@RestController
@RequestMapping("/admin/aggregate")
class AggregateAdminController(
    private val aggregateBackfillRunner: AggregateBackfillRunner,
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
) {
    private val log = LoggerFactory.getLogger(AggregateAdminController::class.java)

    /**
     * 단일 종목 합산 트리거.
     * @param code KIS 6자리 종목 코드 (예: 000660)
     */
    @PostMapping("/{code}")
    fun triggerByCode(
        @PathVariable code: String,
    ): ResponseEntity<AggregateResultResponse> {
        log.info("admin aggregate trigger: code={}", code)
        val result = aggregateBackfillRunner.backfill(code)
        return ResponseEntity.ok(
            AggregateResultResponse(
                triggered = listOf(code),
                results = mapOf(code to result),
            ),
        )
    }

    /**
     * watchlist 전 종목 합산 트리거.
     * 각 종목 순차 실행 (동기). 종목 수 많으면 시간 소요 예상.
     */
    @PostMapping("/all")
    fun triggerAll(): ResponseEntity<AggregateResultResponse> {
        val watchlistEntries = watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc()
        if (watchlistEntries.isEmpty()) {
            log.info("admin aggregate/all: watchlist 종목 없음")
            return ResponseEntity.ok(
                AggregateResultResponse(triggered = emptyList(), results = emptyMap()),
            )
        }

        val codes = watchlistEntries.map { it.code }
        log.info("admin aggregate/all 시작: {} 종목", codes.size)

        val allResults = mutableMapOf<String, Map<String, Int>>()
        for (code in codes) {
            try {
                allResults[code] = aggregateBackfillRunner.backfill(code)
            } catch (e: Exception) {
                log.warn("admin aggregate/all 실패: code={} err={}", code, e.message)
                allResults[code] = emptyMap()
            }
        }

        log.info("admin aggregate/all 완료: {} 종목", codes.size)
        return ResponseEntity.ok(
            AggregateResultResponse(triggered = codes, results = allResults),
        )
    }

    data class AggregateResultResponse(
        /** 트리거된 종목 코드 목록 */
        val triggered: List<String>,
        /** 종목별 interval→insertedCount map */
        val results: Map<String, Map<String, Int>>,
    )
}
