package kr.spdaewoon.magicjar.consumer.aggregation

import kr.spdaewoon.magicjar.consumer.config.MarketDailyBackfillProperties
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * MARKET_DAILY 10년 일봉 backfill 캠페인 admin 트리거 (ADR 0118 R-BF).
 *
 * consumer 모듈 내부 REST (port 8082). 기존 [CandleBackfillAdminController] 패턴 재사용.
 *
 * ## 멱등 트리거 (시동 1회 → 이후 호출마다 progress 이어받기)
 *   - POST /admin/market-daily-backfill/run — 캠페인 1회 실행(멱등 재개·default NORMAL).
 *     매 호출마다 이미 적재 완료 종목 skip + 밤당 콜 cap까지만 처리 + 미완료분 다음 호출 이연.
 *     **default(파라미터 없음·mode=NORMAL)는 절대 wipe 하지 않음** — 기존 멱등 재개 그대로.
 *   - POST /admin/market-daily-backfill/run?mode=MIGRATE — ★ 명시적 게이트. 일봉 패밀리(1d·1wk·1mo·1y)
 *     wipe 선행 후 J 채널 전체 재적재(UN/NX stale 동결 일소 1회용). **1m·5m 는 화이트리스트 LOCK 으로 절대
 *     보존**(실시간 누적·KIS REST 복구 불가). 응답에 wipedRows·mode 노출.
 *   - GET  /admin/market-daily-backfill/progress — universe·완료/잔여 종목 수 관측(콜 0 — count만).
 *
 * ## ★ MIGRATE 실행 예 (curl — 오케스트라 운영 단계 전용, 머지·재배포 후)
 * ```
 * # 일봉 패밀리 wipe → J 채널 전체 재적재 (ENABLED=true 선행 필수)
 * curl -X POST 'http://localhost:8082/admin/market-daily-backfill/run?mode=MIGRATE'
 * # 응답: {"mode":"MIGRATE","wipedRows":<삭제 row 수>,"status":"CAP_REACHED|COMPLETED",...}
 * # cap(default 6,000콜) 도달 시 미완분은 mode 불문 POST /run 반복 호출로 이어서 재적재(멱등).
 * ```
 *
 * ## ENV gate (시동/중단)
 *   `magicjar.market-daily-backfill.enabled` (MAGICJAR_MARKET_DAILY_BACKFILL_ENABLED)
 *     - false(default) → run 호출해도 DISABLED 즉시 종료(가동 0, 적재분 보존 — 중단 = off).
 *     - true → 캠페인 가동. 매 run 호출이 progress 이어받아 미완료만 처리.
 *
 * ## rate-limit (ADR 0118 §5-1 LOCK)
 *   캠페인 모든 KIS 콜은 BACKFILL(BG) 버킷 고정 — USER_PATH·INGESTION 침범 0.
 *   8~12h 점유해도 자본·사용자 path 영향 0.
 *
 * 자본 영향 0 — 데이터 적재만.
 *
 * ## ⚠ full 실행 주의
 *   본 endpoint 1회 호출은 cap(default 6,000콜)까지만 처리한다. 전종목 10년(~75,000콜) full 완주는
 *   여러 밤/주말에 걸친 반복 호출(또는 R2 snapshot 스케줄러 배선)로 이뤄지며, **실 가동은 사용자 환경 + 명시 go 시점**이다.
 */
@RestController
@RequestMapping("/admin/market-daily-backfill")
class MarketDailyBackfillAdminController(
    private val campaign: MarketDailyBackfillCampaign,
    private val properties: MarketDailyBackfillProperties,
    private val dateRepairService: MarketDailyDateRepairService,
) {
    private val log = LoggerFactory.getLogger(MarketDailyBackfillAdminController::class.java)

    /**
     * @param mode `NORMAL`(default·wipe 0·멱등 재개) 또는 `MIGRATE`(일봉 패밀리 wipe 후 J 채널 전체 재적재).
     *   대소문자 무관. 미지정·미인식 값은 안전하게 NORMAL(절대 wipe 0)로 처리.
     */
    @PostMapping("/run")
    fun run(
        @RequestParam(name = "mode", required = false, defaultValue = "NORMAL") mode: String,
    ): ResponseEntity<CampaignResultResponse> {
        val resolvedMode =
            when (mode.trim().uppercase()) {
                "MIGRATE" -> MarketDailyBackfillCampaign.BackfillMode.MIGRATE
                else -> MarketDailyBackfillCampaign.BackfillMode.NORMAL // 미인식 값은 안전하게 NORMAL(wipe 0)
            }
        log.info(
            "admin market-daily-backfill run trigger: enabled={} modeParam={} resolvedMode={}",
            properties.enabled,
            mode,
            resolvedMode,
        )
        val r = campaign.runCampaign(resolvedMode)
        return ResponseEntity.ok(
            CampaignResultResponse(
                runId = r.runId,
                status = r.status.name,
                mode = r.mode.name,
                wipedRows = r.wipedRows,
                universeSize = r.universeSize,
                alreadyComplete = r.alreadyComplete,
                processedSymbols = r.processedSymbols,
                newlyCompleteSymbols = r.newlyCompleteSymbols,
                insertedRows = r.insertedRows,
                callsConsumed = r.callsConsumed,
                callCap = r.callCap,
                failedSymbols = r.failedSymbols,
                skippedThrottle = r.skippedThrottle,
                coolingDownSymbols = r.coolingDownSymbols,
                remainingSymbols = r.remainingSymbols,
                durationMs = r.durationMs,
                enabled = properties.enabled,
            ),
        )
    }

    /**
     * ★ 특정 날짜 1d 정밀 복구 (ADR 0118 R-BF amend — 2026-06-15 06-12 손상 row 교정).
     *
     * 대상 날짜 전종목 1d 봉을 KIS daily range 로 재fetch → 정상값은 그 날짜 1봉만 upsert(DO UPDATE),
     * KIS 미보유/여전히 0값은 손상 row 정확 1행 DELETE → 대상 날짜 소속 주/월/년봉 재롤업.
     * **1d 패밀리 전체 wipe 0 — 대상 날짜 1d 1봉만 교정.** NORMAL/MIGRATE 경로 무변경.
     *
     * 비동기 — 즉시 runId(accepted) 반환 후 백그라운드 완주(curl 타임아웃 회피). 진행/결과는 GET /repair-date/status.
     *
     * ## ★ 06-12 복구 실행 예 (curl — 머지·재배포 + ENABLED=true 선행)
     * ```
     * curl -X POST 'http://localhost:8082/admin/market-daily-backfill/repair-date?date=2026-06-12'
     * # 응답(accepted): {"runId":...,"status":"COMPLETED","targetDate":"2026-06-12","universeSize":2517,...}
     * # 완주 결과 관측:
     * curl 'http://localhost:8082/admin/market-daily-backfill/repair-date/status'
     * ```
     *
     * @param date ISO-8601 `yyyy-MM-dd`(KST 기준). 형식 오류면 400.
     */
    @PostMapping("/repair-date")
    fun repairDate(
        @RequestParam(name = "date") date: String,
    ): ResponseEntity<DateRepairResponse> {
        val parsed =
            try {
                LocalDate.parse(date.trim())
            } catch (e: DateTimeParseException) {
                log.warn("admin market-daily-backfill repair-date 날짜 파싱 실패: date={} err={}", date, e.message)
                return ResponseEntity.badRequest().build()
            }
        log.info(
            "admin market-daily-backfill repair-date trigger: enabled={} date={}",
            properties.enabled,
            parsed,
        )
        val r = dateRepairService.repairDateAsync(parsed)
        return ResponseEntity.ok(toDateRepairResponse(r))
    }

    /** 특정 날짜 1d 복구 진행/결과 관측 — 비동기 완주 결과(없으면 lastResult=null). */
    @GetMapping("/repair-date/status")
    fun repairDateStatus(): ResponseEntity<DateRepairStatusResponse> {
        val v = dateRepairService.statusView()
        return ResponseEntity.ok(
            DateRepairStatusResponse(
                running = v.running,
                enabled = properties.enabled,
                lastResult = v.lastResult?.let { toDateRepairResponse(it) },
            ),
        )
    }

    private fun toDateRepairResponse(r: MarketDailyDateRepairService.RepairResult): DateRepairResponse =
        DateRepairResponse(
            runId = r.runId,
            status = r.status.name,
            targetDate = r.targetDate,
            universeSize = r.universeSize,
            processedSymbols = r.processedSymbols,
            repairedSymbols = r.repairedSymbols,
            upsertedRows = r.upsertedRows,
            deletedCorruptRows = r.deletedCorruptRows,
            stillZeroSymbols = r.stillZeroSymbols,
            failedSymbols = r.failedSymbols,
            rollupWeeklyUpserted = r.rollupWeeklyUpserted,
            rollupMonthlyUpserted = r.rollupMonthlyUpserted,
            rollupYearlyUpserted = r.rollupYearlyUpserted,
            durationMs = r.durationMs,
            enabled = properties.enabled,
        )

    @GetMapping("/progress")
    fun progress(): ResponseEntity<ProgressResponse> {
        val total = campaign.universeCount()
        return ResponseEntity.ok(
            ProgressResponse(
                enabled = properties.enabled,
                universeSize = total,
                historyYears = properties.normalizedHistoryYears(),
                nightlyCallCap = properties.normalizedNightlyCallCap(),
                note = "잔여/완료 정확 산출은 POST /run 의 응답(remainingSymbols) 참조 — progress 는 universe 규모 관측만(콜 0).",
            ),
        )
    }

    data class CampaignResultResponse(
        val runId: String,
        val status: String,
        val mode: String,
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
        val enabled: Boolean,
    )

    data class ProgressResponse(
        val enabled: Boolean,
        val universeSize: Long,
        val historyYears: Int,
        val nightlyCallCap: Int,
        val note: String,
    )

    data class DateRepairResponse(
        val runId: String,
        val status: String,
        val targetDate: String,
        val universeSize: Int,
        val processedSymbols: Int,
        val repairedSymbols: Int,
        val upsertedRows: Long,
        val deletedCorruptRows: Long,
        val stillZeroSymbols: Int,
        val failedSymbols: Int,
        val rollupWeeklyUpserted: Long,
        val rollupMonthlyUpserted: Long,
        val rollupYearlyUpserted: Long,
        val durationMs: Long,
        val enabled: Boolean,
    )

    data class DateRepairStatusResponse(
        val running: Boolean,
        val enabled: Boolean,
        val lastResult: DateRepairResponse?,
    )
}
