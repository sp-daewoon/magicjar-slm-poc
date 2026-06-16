package kr.spdaewoon.magicjar.api.controller.screener

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.screener.DaytradeDiscoverService
import kr.spdaewoon.magicjar.api.service.screener.DiscoveryScorecardQueryService
import kr.spdaewoon.magicjar.api.service.screener.ScreenerService
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Screener REST 컨트롤러 — 3 endpoint (ADR 0071 §6 + spec §6 + 사용자 메모리 feedback_swagger_pattern).
 *
 * Swagger 4단 LOCK:
 * - @Tag: 컨트롤러 레벨
 * - @Operation: 메서드 레벨
 * - @Parameter: 파라미터 레벨
 * - DTO @Schema: DTO 필드 레벨
 *
 * 4 endpoint:
 * - POST /api/screener/evaluate — 동기 inline 평가 (A-1 채택, strategyId 필수)
 * - POST /api/screener/daytrade-discover — 룰 기반 단타 발굴 (strategyId 없이 빌트인 프리셋, ADR 0117 R2)
 * - GET /api/screener/runs — 이력 조회 (옵션 영속 ON 시)
 * - GET /api/screener/runs/{id} — 단건 상세 (옵션 영속 ON 시)
 *
 * AI 호출 0 LOCK (ADR 0071 §2-9 + ADR 0117 §2-3 + 개발 룰 5). daytrade-discover는 룰 기반 — `ai-discovery`(LLM)와 분리.
 * KIS endpoint 호출 0 LOCK. 요청 interval은 DB에 있는 기준봉을 선택하며, 결손은 skip 사유로 노출한다.
 */
@Tag(name = "Screener", description = "종목 발굴 (universe × DSL → 후보 + 랭킹) — ADR 0071 T-SC Phase 2 + ADR 0117 단타 발굴")
@RestController
@RequestMapping("/api/screener")
class ScreenerController(
    private val screenerService: ScreenerService,
    private val daytradeDiscoverService: DaytradeDiscoverService,
    private val discoveryScorecardQueryService: DiscoveryScorecardQueryService,
) {
    /**
     * 전략으로 종목 찾기 (inline 동기 평가).
     *
     * 등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
     * 30분 내 동일 DSL + 동일 필터 재호출 시 Redis 캐시 반환 (idempotency, cacheHit=true + elapsedMs < 100ms).
     * universe ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
     * 동시 호출 시 분산락 — 1회 inline 평가 + 1회 캐시 fetch 또는 409 Conflict.
     */
    @Operation(
        summary = "전략으로 종목 찾기 (inline 평가)",
        description = """
            등록된 Strategy DSL을 universe(KOSPI·KOSDAQ active instrument)에 적용해 매수 후보 + 랭킹 반환.
            요청 interval(기본 1d)을 기준봉으로 사용하며, 분봉/일봉/주봉/월봉/년봉 모두 DB 데이터가 있으면 평가할 수 있습니다.
            30분 내 동일 strategy + 동일 interval + 동일 필터 재호출 시 캐시 반환 (idempotency).
            universe size ~3000 × DSL eval 500ms timeout = 최대 inline ~25s.
            동시 호출은 분산락 + 캐시 fetch 또는 409 Conflict.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening 결과 + 랭킹"),
            ApiResponse(responseCode = "400", description = "요청 파라미터 오류 (userId·strategyId·limit 검증 실패)"),
            ApiResponse(responseCode = "409", description = "동시 평가 중 (분산락 충돌 + 캐시 부재)"),
            ApiResponse(responseCode = "500", description = "DSL 평가 엔진 오류"),
        ],
    )
    @PostMapping("/evaluate")
    fun evaluate(
        @Valid @RequestBody request: ScreenerEvaluateRequest,
    ): ResponseEntity<ScreenerResultDto> {
        val result = screenerService.evaluate(request)
        return ResponseEntity.ok(result)
    }

    /**
     * 단타 발굴 (룰 기반 — 전략 없이 시스템 빌트인 프리셋으로 오늘의 후보 TOP N).
     *
     * 사용자가 전략을 미리 작성하지 않아도, 시스템 빌트인 단타 setup 프리셋(V75 시드 `DAYTRADE_*`)의
     * entry_dsl을 universe(KOSPI·KOSDAQ active instrument)에 적용해 후보 + 랭킹을 반환합니다.
     * 장전 setup = 일봉(interval=1d) 기준 — candle 영속 reuse, KIS 호출 0.
     *
     * `startRealtimeWatch=true`면 후보를 STRATEGY owner WS 수요로 등록합니다 (watchlist 자동 삽입 0 — 후보 출발점).
     * 룰 기반 발굴이며 LLM 호출 0 — `/api/strategy-research/ai-discovery`(LLM 발굴)와 개념·path가 분리됩니다.
     */
    @Operation(
        summary = "단타 발굴 (룰 기반 — 전략 없이 빌트인 프리셋으로 후보 TOP N)",
        description = """
            시스템 빌트인 단타 setup 프리셋(V75 시드 DAYTRADE_*)으로 universe를 평가해 오늘의 단타 후보 + 랭킹 반환.
            presetKey null이면 활성 DAYTRADE_* 프리셋 전체 합집합으로 발굴합니다.
            장전 setup = 일봉(interval=1d) 기준. candle 영속 reuse — KIS 호출 0. 결손은 skip 사유로 정직 노출(mock 0).
            startRealtimeWatch=true면 후보를 STRATEGY owner 실시간 관찰 수요로 등록(watchlist write 0 — 후보 출발점, 매매 결정권 0).
            룰 기반 발굴 — LLM 호출 0 (ai-discovery 와 구분).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발굴 후보 + 랭킹 + 프리셋 메타"),
            ApiResponse(responseCode = "400", description = "요청 파라미터 오류 (userId·interval·limit 검증 실패)"),
            ApiResponse(responseCode = "404", description = "presetKey에 해당하는 발굴 프리셋 미존재"),
            ApiResponse(responseCode = "422", description = "프리셋 entry_dsl 파싱 실패 (전 프리셋)"),
            ApiResponse(responseCode = "503", description = "단타 발굴 비활성 (MAGICJAR_DAYTRADE_DISCOVERY_ENABLED=false)"),
        ],
    )
    @PostMapping("/daytrade-discover")
    fun daytradeDiscover(
        @Valid @RequestBody request: DaytradeDiscoverRequest,
    ): ResponseEntity<DaytradeDiscoverResultDto> {
        val result = daytradeDiscoverService.discover(request)
        return ResponseEntity.ok(result)
    }

    /**
     * screening 실행 이력 조회 (옵션 영속 ON 시).
     *
     * MAGICJAR_SCREENER_PERSIST=true 환경에서만 활성.
     * false 환경에서는 empty page 반환 (404 대신 빈 페이지 — feed_no_mock_data 정합).
     */
    @Operation(
        summary = "screening 실행 이력 조회 (옵션 영속 모드 한정)",
        description = """
            MAGICJAR_SCREENER_PERSIST=true 환경에서만 데이터 반환.
            영속 OFF 시 empty page 반환.
            최신순 페이징 (startedAt DESC).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening_run 페이징 결과 (영속 OFF 시 empty page)"),
            ApiResponse(responseCode = "400", description = "파라미터 오류"),
        ],
    )
    @GetMapping("/runs")
    fun listRuns(
        @Parameter(description = "사용자 ID (multi-tenant 격리)", example = "12345678", required = true)
        @RequestParam userId: Long,
        @Parameter(description = "페이지 번호 (0-based)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지당 결과 수 (최대 100)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<ScreeningRunDto> {
        val safeSize = size.coerceIn(1, 100)
        return screenerService.listRuns(userId, page, safeSize)
    }

    /**
     * screening 단건 상세 조회 (옵션 영속 ON 시).
     *
     * userId mismatch 시 404 반환 (ADR 0010 multi-tenant 격리).
     * 영속 OFF 시 404 반환.
     */
    @Operation(
        summary = "screening 단건 상세 (옵션 영속 모드 한정)",
        description = """
            screening_run + screening_match 배열 상세.
            userId mismatch 시 404 반환 (multi-tenant 격리 ADR 0010).
            MAGICJAR_SCREENER_PERSIST=false 시 404 반환.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "screening_run 단건 상세 + match 배열"),
            ApiResponse(responseCode = "404", description = "미존재 또는 userId mismatch 또는 영속 모드 OFF"),
        ],
    )
    @GetMapping("/runs/{id}")
    fun findDetail(
        @Parameter(description = "screening 실행 ID", example = "1001", required = true)
        @PathVariable id: Long,
        @Parameter(description = "사용자 ID (격리 검증)", example = "12345678", required = true)
        @RequestParam userId: Long,
    ): ScreeningRunDetailDto =
        screenerService.findDetail(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "screening run 미존재 또는 접근 불가 (id=$id, userId=$userId)")

    /**
     * 발굴 스코어카드 조회 (forward-test hit-rate).
     *
     * 매일 장전 발굴 TOP N을 기록하고 다음 거래일 실제 일봉으로 자동 채점한 살아있는 증거를 반환합니다.
     * 기간(from~to)·프리셋(presetKey)별 요약(평균 다음날 수익률·hit rate=양수 비율·표본 수) + 상세 rows.
     * 미채점 row(일봉 미수집 등)는 채점 필드 null로 정직하게 노출(mock 0). 관찰·기록 전용 — 매매 결정권 0.
     */
    @Operation(
        summary = "발굴 스코어카드 조회 (forward-test 요약 + 상세)",
        description = """
            매일 장전 발굴 TOP N 기록 + 다음 거래일 실제 일봉 자동 채점 결과.
            from~to(기본 최근 30일)·presetKey(null이면 전체)별 요약 — 평균 다음날 종가/시초가 수익률, hit rate(양수 비율), 표본 수.
            상세 rows는 snapshot_date DESC·rank ASC. 미채점 row는 채점 필드 null(mock 0).
            read-only — 자동매매 결정권 0, watchlist write 0, KIS 호출 0.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발굴 스코어카드 요약 + 상세"),
            ApiResponse(responseCode = "400", description = "from > to 등 기간 파라미터 오류"),
        ],
    )
    @GetMapping("/scorecard")
    fun scorecard(
        @Parameter(description = "조회 시작일 (포함, ISO yyyy-MM-dd). 미지정 시 to-30일", example = "2026-05-10")
        @RequestParam(required = false)
        from: java.time.LocalDate?,
        @Parameter(description = "조회 종료일 (포함, ISO yyyy-MM-dd). 미지정 시 오늘(KST)", example = "2026-06-10")
        @RequestParam(required = false)
        to: java.time.LocalDate?,
        @Parameter(description = "프리셋 key 필터 (null이면 전체 DAYTRADE_* 프리셋)", example = "DAYTRADE_GAP_RVOL_TURNOVER")
        @RequestParam(required = false)
        presetKey: String?,
        @Parameter(description = "상세 rows 최대 개수 (1~2000, default 500)", example = "500")
        @RequestParam(defaultValue = "500")
        maxRows: Int,
    ): DiscoveryScorecardResponseDto {
        val end = to ?: java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
        val start = from ?: end.minusDays(30)
        if (start.isAfter(end)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from($start)이 to($end)보다 이후입니다")
        }
        return discoveryScorecardQueryService.query(start, end, presetKey, maxRows)
    }
}
