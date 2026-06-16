package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.CandleHealthService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 캔들 수집 건강도 REST 컨트롤러 — readiness P1 (2026-06-04 평가 §B-1a).
 *
 * 화면에서 "왜 수집이 안 됐는지"를 종목별 1줄로 표시하기 위한 진단 endpoint.
 * - GET /api/symbols/{code}/candle-health        — 단건 진단
 * - GET /api/symbols/candle-health?codes=...      — 배치 진단(watchlist 한눈에)
 *
 * 판정은 [CandleHealthService] 가 공유 인프라(DB·Redis)만 읽어 어셈블한다(consumer HTTP 호출 0).
 * OnboardingGuardFilter 통과 의무(기존 /api/symbols 패턴 정합).
 * Swagger 4단 어노테이션(feedback_swagger_pattern 정합).
 * mock 합성 금지 — 판정 불가 시 reason=UNKNOWN 정직 반환.
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "CandleHealth", description = "캔들 수집 건강도 진단 — stale 원인(WS 미구독·tick 없음·백필 실패·장외) 1줄 표시")
@RestController
@RequestMapping("/api/symbols")
class CandleHealthController(
    private val candleHealthService: CandleHealthService,
) {
    private val log = LoggerFactory.getLogger(CandleHealthController::class.java)

    @Operation(
        summary = "캔들 수집 건강도(단건) 진단",
        description =
            "지정 종목·interval의 캔들 수집 상태와 정체 원인을 1줄로 반환합니다. " +
                "status: FRESH(정상)·STALE(정체)·NO_DATA(0건). " +
                "reason: OK_REALTIME·MARKET_CLOSED(장외 정상)·WS_NOT_SUBSCRIBED(실시간 미구독)·" +
                "NO_TICK(구독했으나 체결 미도착)·BACKFILL_FAILED(백필 재시도 중 실패)·" +
                "BACKFILL_PERMANENT_GAP(KIS 보관 한도 밖 영구 갭)·UNKNOWN(특정 불가). " +
                "판정 우선순위: 장외 > 영구갭 > 백필실패 > WS미구독 > tick없음 > UNKNOWN. " +
                "공유 인프라(DB candle·Redis)만 읽어 판정하며 KIS 추가 호출 0. mock 합성 없음.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "진단 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 종목코드",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(responseCode = "401", description = "셋업 미완 — OnboardingGuardFilter 차단"),
    )
    @GetMapping("/{code}/candle-health")
    fun getCandleHealth(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(
            description = "점검 interval (default 1m — 실시간 수집 1차 권위). 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y",
            example = "1m",
        )
        @RequestParam(defaultValue = CandleHealthService.DEFAULT_INTERVAL) interval: String,
    ): CandleHealthDto {
        validateCode(code)
        return candleHealthService.assess(code, interval).toDto()
    }

    @Operation(
        summary = "캔들 수집 건강도(배치) 진단",
        description =
            "여러 종목의 캔들 수집 상태를 한 번에 진단합니다 (watchlist 한눈에 보기). " +
                "codes는 콤마 구분 6자리 종목코드 CSV(최대 50종목). " +
                "각 item 구조는 단건 진단과 동일하며 codes 입력 순서를 보존합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "진단 성공"),
        ApiResponse(
            responseCode = "400",
            description = "codes 누락·형식 오류·50종목 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @GetMapping("/candle-health")
    fun getCandleHealthBatch(
        @Parameter(description = "종목코드 CSV (6자리, 최대 50종목)", example = "005930,000660")
        @RequestParam codes: String,
        @Parameter(
            description = "점검 interval (default 1m). 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y",
            example = "1m",
        )
        @RequestParam(defaultValue = CandleHealthService.DEFAULT_INTERVAL) interval: String,
    ): CandleHealthBatchDto {
        val codeList = codes.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (codeList.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "codes를 1개 이상 지정해 주세요 (예: 005930,000660)")
        }
        if (codeList.size > MAX_BATCH_CODES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "한 번에 최대 ${MAX_BATCH_CODES}종목까지 진단할 수 있습니다")
        }
        codeList.forEach { validateCode(it) }
        val items = candleHealthService.assessAll(codeList, interval).map { it.toDto() }
        return CandleHealthBatchDto(items = items)
    }

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
    }

    private fun CandleHealthService.CandleHealth.toDto(): CandleHealthDto =
        CandleHealthDto(
            code = code,
            interval = interval,
            lastCandleAt = lastCandleAt?.toString(),
            staleSeconds = staleSeconds,
            status = status.name,
            reason = reason.name,
            reasonDetail = reasonDetail,
            checkedAt = checkedAt.toString(),
        )

    private companion object {
        const val MAX_BATCH_CODES = 50
    }
}

@Schema(description = "캔들 수집 건강도 진단 결과")
data class CandleHealthDto(
    @field:Schema(description = "종목코드", example = "005930")
    val code: String,
    @field:Schema(description = "점검 interval", example = "1m")
    val interval: String,
    @field:Schema(description = "마지막 캔들 시각(ISO8601, KST offset). 0건이면 null", example = "2026-06-05T09:41:00Z", nullable = true)
    val lastCandleAt: String?,
    @field:Schema(description = "마지막 캔들 이후 경과 초. 0건이면 null", example = "65", nullable = true)
    val staleSeconds: Long?,
    @field:Schema(description = "FRESH·STALE·NO_DATA", example = "STALE")
    val status: String,
    @field:Schema(
        description =
            "정체 원인 코드 — OK_REALTIME·MARKET_CLOSED·WS_NOT_SUBSCRIBED·NO_TICK·" +
                "INSERT_FAILED·BACKFILL_FAILED·BACKFILL_PERMANENT_GAP·UNKNOWN",
        example = "WS_NOT_SUBSCRIBED",
    )
    val reason: String,
    @field:Schema(description = "사람이 읽는 원인 1줄(한국어)", example = "정규장 중이나 실시간(KIS WS) 구독이 없습니다")
    val reasonDetail: String,
    @field:Schema(description = "판정 시각(ISO8601)", example = "2026-06-05T09:42:05Z")
    val checkedAt: String,
)

@Schema(description = "캔들 수집 건강도 배치 진단 결과")
data class CandleHealthBatchDto(
    @field:Schema(description = "종목별 진단 결과 (codes 입력 순서 보존)")
    val items: List<CandleHealthDto>,
)
