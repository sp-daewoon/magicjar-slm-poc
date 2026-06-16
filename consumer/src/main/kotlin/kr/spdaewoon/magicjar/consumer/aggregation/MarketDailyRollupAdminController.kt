package kr.spdaewoon.magicjar.consumer.aggregation

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전종목 1wk/1mo/1y 1d 롤업 백필 admin 트리거 (ADR 0120 — 년봉 회귀 복구).
 *
 * consumer 모듈 내부 REST (port 8082). [MarketDailyBackfillAdminController] 패턴 재사용.
 *
 * ## 트리거 (비동기 · 멱등 — upsert DO UPDATE · ADR 0120 amend 2026-06-10)
 *   - POST /admin/market-daily-rollup/run — 전종목 1d → 1wk/1mo/1y full-regen 합산(KIS 콜 0).
 *     **즉시 202 ACCEPTED + runId 반환 후 백그라운드 병렬 실행** — curl 10분 타임아웃 회피.
 *     초판 실측(~3종목/분 → 45/2,517 만)을 batch upsert + 코루틴 병렬 + 비동기로 fix.
 *     진행은 로그·DB audit·`GET /status` 로 관측. 동시 1건만(중복 트리거는 409 ALREADY_RUNNING).
 *   - GET /admin/market-daily-rollup/status — 진행 중 여부 + 마지막 완료 결과(관측, 콜 0).
 *
 * ## 실행 예 (curl — 오케스트라 운영 단계 전용, 머지·재배포 후)
 * ```
 * # 1) 비동기 트리거 — 즉시 202 + runId (백그라운드 병렬 실행)
 * curl -X POST 'http://localhost:8082/admin/market-daily-rollup/run'
 * # 응답(202): {"runId":"mkt-daily-rollup:...","accepted":true,"universeSize":2517, ...}
 * # 2) 진행 관측 — 완주까지 폴링
 * curl 'http://localhost:8082/admin/market-daily-rollup/status'
 * # 응답: {"running":true|false,"lastResult":{"status":"COMPLETED","processedSymbols":...,
 * #        "weeklyUpserted":...,"monthlyUpserted":...,"yearlyUpserted":...,"durationMs":...}}
 * ```
 *
 * ## 전제
 *   R-BF(POST /admin/market-daily-backfill/run)로 1d 전종목 10년이 먼저 적재되어 있어야 한다
 *   (1d 0건 종목은 skippedNoDaily 로 집계). R2 snapshot 스케줄러는 매 영업일 1d snapshot 직후
 *   1wk/1mo/1y 를 자동 재롤업하므로(incremental), 본 admin 은 대량 1회 복구 + 수동 재실행(full-regen) 경로다.
 *
 * 자본 영향 0 — 데이터 적재만. KIS 콜 0.
 */
@RestController
@RequestMapping("/admin/market-daily-rollup")
class MarketDailyRollupAdminController(
    private val rollupService: MarketDailyRollupService,
) {
    private val log = LoggerFactory.getLogger(MarketDailyRollupAdminController::class.java)

    @PostMapping("/run")
    fun run(): ResponseEntity<RollupAcceptResponse> {
        log.info("admin market-daily-rollup run trigger (비동기 · 전종목 1d → 1wk/1mo/1y full-regen, KIS 콜 0)")
        val r = rollupService.runRollupAsync()
        val (httpStatus, accepted) =
            when (r.status) {
                MarketDailyRollupService.RollupStatus.ALREADY_RUNNING -> HttpStatus.CONFLICT to false
                MarketDailyRollupService.RollupStatus.EMPTY_UNIVERSE -> HttpStatus.OK to false
                else -> HttpStatus.ACCEPTED to true // COMPLETED placeholder = ACCEPTED(백그라운드 진행)
            }
        return ResponseEntity.status(httpStatus).body(
            RollupAcceptResponse(
                runId = r.runId,
                status = r.status.name,
                accepted = accepted,
                universeSize = r.universeSize,
                note = "비동기 — 완주 결과는 GET /admin/market-daily-rollup/status 로 폴링(running=false 시 lastResult 확정).",
            ),
        )
    }

    @GetMapping("/status")
    fun status(): ResponseEntity<RollupStatusResponse> {
        val view = rollupService.statusView()
        val last = view.lastResult
        return ResponseEntity.ok(
            RollupStatusResponse(
                running = view.running,
                lastResult =
                    last?.let {
                        RollupResultResponse(
                            runId = it.runId,
                            status = it.status.name,
                            universeSize = it.universeSize,
                            processedSymbols = it.processedSymbols,
                            skippedNoDaily = it.skippedNoDaily,
                            weeklyUpserted = it.weeklyUpserted,
                            monthlyUpserted = it.monthlyUpserted,
                            yearlyUpserted = it.yearlyUpserted,
                            failedSymbols = it.failedSymbols,
                            durationMs = it.durationMs,
                        )
                    },
            ),
        )
    }

    data class RollupAcceptResponse(
        val runId: String,
        val status: String,
        val accepted: Boolean,
        val universeSize: Int,
        val note: String,
    )

    data class RollupStatusResponse(
        val running: Boolean,
        val lastResult: RollupResultResponse?,
    )

    data class RollupResultResponse(
        val runId: String,
        val status: String,
        val universeSize: Int,
        val processedSymbols: Int,
        val skippedNoDaily: Int,
        val weeklyUpserted: Long,
        val monthlyUpserted: Long,
        val yearlyUpserted: Long,
        val failedSymbols: Int,
        val durationMs: Long,
    )
}
