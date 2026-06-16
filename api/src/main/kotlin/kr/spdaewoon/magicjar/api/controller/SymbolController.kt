package kr.spdaewoon.magicjar.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import kr.spdaewoon.magicjar.api.service.CandleRecollectJobService
import kr.spdaewoon.magicjar.api.service.CandleRefreshService
import kr.spdaewoon.magicjar.api.service.SymbolMetaService
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectJobResponse
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectProgressEvent
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectRangeRequest
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectResponse
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 종목 REST 컨트롤러 (spec §3-1 LOCK — 4 endpoint).
 *
 * - GET  /api/symbols/{code}                        — SymbolDto (현재가·PER·PBR·시총·업종)
 * - GET  /api/symbols/{code}/candles                — CandlesResponseDto (차트 series)
 * - POST /api/symbols/{code}/realtime/subscribe     — 현재가 + 호가 slot ENTER 명령(표시 중에만 — 2026-06-12)
 * - POST /api/symbols/{code}/realtime/unsubscribe   — 현재가 + 호가 slot EXIT 명령(이탈 즉시 해제)
 * - POST /api/symbols/{code}/orderbook/subscribe    — deprecated 호환 alias(realtime/subscribe와 동일 ENTER)
 * - POST /api/symbols/{code}/orderbook/unsubscribe  — deprecated 호환 alias(realtime/unsubscribe와 동일 EXIT)
 *
 * OnboardingGuardFilter 통과 의무 (PR-6 정합 — 자동).
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Tag(name = "Symbol", description = "종목 상세 REST — 현재가·캔들·호가(상세 표시 중)·캔들 갱신 (T-S1). 호가는 전략 ACTIVE + 종목 상세 표시 중 두 경로(2026-06-12)")
@RestController
@RequestMapping("/api/symbols")
class SymbolController(
    private val symbolMetaService: SymbolMetaService,
    private val candleQueryService: CandleQueryService,
    private val candleRefreshService: CandleRefreshService,
    private val candleRecollectJobService: CandleRecollectJobService,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val userAccountJpaRepository: UserAccountJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SymbolController::class.java)

    @Operation(
        summary = "종목 메타·현재가 조회",
        description =
            "종목코드(6자리)로 현재가·전일대비·등락률·PER·PBR·시총·업종·종목명을 반환합니다. " +
                "1순위: 신선한 Redis quote:last(5분 이내 실시간), 2순위: KIS PriceMetrics J/KRX 스냅샷, " +
                "3순위: DB 1d candle fallback. 데이터 부재 시 503.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "종목 데이터 없음 — KIS·Redis 모두 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}")
    fun getSymbol(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): SymbolDto {
        validateCode(code)
        return try {
            symbolMetaService.getSymbol(code)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("getSymbol 오류: code={} err={}", code, e.message)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "종목 데이터를 가져올 수 없습니다",
            )
        }
    }

    @Operation(
        summary = "종목 캔들(차트) 시계열 조회",
        description =
            "interval 인터벌의 캔들 시계열을 반환합니다 (DB candle + Redis current·today 합류). " +
                "interval: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y. " +
                "before: 페이지네이션 cursor (ISO8601, 미지정 시 최신). " +
                "limit: 1 ≤ limit ≤ 5000 (default 600 — ADR 0093 §3-1 LOCK). " +
                "응답 candles는 ts ASC 정렬 (LWC v4 setData 기대). " +
                "hasMore:true이면 nextBefore cursor로 추가 fetch 가능. " +
                "데이터 0건이면 candles:[] + hasMore:false (graceful — '데이터 수집 중' 표시).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 파라미터 (interval 형식 오류 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/candles")
    fun getCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)", example = "1d")
        @RequestParam(defaultValue = "1d") interval: String,
        @Parameter(description = "페이지네이션 cursor — 이 시각 이전 candle 반환 (ISO8601)", example = "2026-05-07T15:30:00Z")
        @RequestParam(required = false) before: String?,
        @Parameter(description = "응답 캔들 수 (1~5000, default 600 — ADR 0093 §3-1 LOCK)", example = "600")
        @RequestParam(defaultValue = "600") limit: Int,
    ): CandlesResponseDto {
        validateCode(code)
        if (interval !in candleQueryService.supportedIntervals) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 interval: $interval (허용: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            )
        }
        return candleQueryService.getCandles(code, interval, before, limit)
    }

    @Operation(
        summary = "캔들 수동 갱신",
        description =
            "지정 종목의 캔들 데이터를 KIS OpenAPI에서 즉시 fetch하여 DB에 upsert합니다 (dogfooding qa #5). " +
                "intervals 파라미터로 갱신 대상 인터벌 지정 (기본값: 1d,1wk,1mo,1y). " +
                "분봉(1m) 포함 시 직전 30 거래일 분봉 전수 수집. " +
                "KIS-first: FHKST03010100(일/주/월/연봉), FHKST03010230(1m) 직접 호출. " +
                "KIS raw: open-trading-api/examples_llm/domestic_stock/inquire_daily_itemchartprice " +
                "응답: refreshedIntervals 배열 (interval, fetchedCount, insertedCount, latestTs, status).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "갱신 성공 (일부 interval 실패 시에도 200 — status 필드로 구분)"),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "429",
            description = "KIS Rate Limit 초과",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "KIS 인증 실패 또는 내부 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{code}/candles/refresh")
    fun refreshCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "006110")
        @PathVariable code: String,
        @Parameter(
            description = "갱신 대상 interval CSV (기본값: 1d,1wk,1mo,1y). 지원: 1m,1d,1wk,1mo,1y",
            example = "1d,1wk,1mo,1y",
        )
        @RequestParam(defaultValue = "") intervals: String,
    ): CandleRefreshResponse {
        validateCode(code)
        val intervalList =
            if (intervals.isBlank()) {
                emptyList()
            } else {
                intervals.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        return try {
            candleRefreshService.refresh(code, intervalList)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("refreshCandles 오류: code={} err={}", code, e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "캔들 갱신 중 오류가 발생했습니다")
        }
    }

    @Operation(
        summary = "캔들 초기화 후 재수집",
        description =
            "차트 캔들 데이터가 잘못 수집된 것으로 보일 때 사용자가 명시적으로 실행하는 복구 endpoint입니다. " +
                "지정 interval의 기존 DB candle을 삭제한 뒤 KIS OpenAPI에서 다시 수집합니다. " +
                "3m·5m·15m·30m·90m·1h·4h·12h는 KIS 직접 제공 interval이 아니므로 1m을 함께 재수집한 뒤 서버에서 합산 재생성합니다. " +
                "Mock·합성 보정 없이 KIS/DB 기준 데이터만 사용합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재수집 완료 (일부 interval 실패 시 status 필드로 구분)"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 interval 또는 파라미터",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "500",
            description = "KIS 인증 실패 또는 내부 오류",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{code}/candles/recollect")
    fun recollectCandles(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(
            description = "재수집 대상 interval CSV. 합산봉(3m~12h)은 1m도 함께 재수집 후 재생성",
            example = "3m",
        )
        @RequestParam(defaultValue = "") intervals: String,
        @Parameter(description = "범위 재수집 시작 시각 ISO-8601. to와 함께 지정하면 전체 삭제 대신 범위만 재수집합니다.")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "범위 재수집 종료 시각 ISO-8601")
        @RequestParam(required = false) to: String?,
        @Parameter(description = "from/to 앞뒤 거래일 padding. 리서치 기본값은 5.")
        @RequestParam(defaultValue = "0") paddingTradingDays: Int,
        @Parameter(
            description =
                "ADR 0120 §6 재수집 모달 — 1m 기준봉(+합산 파생)만 최근 N 거래일로 제한. " +
                    "일봉 패밀리(1d·1wk·1mo·1y)는 전기간 강제 재수집 유지. from/to(리서치 range) 지정 시 무시됨.",
        )
        @RequestParam(required = false) minuteRecentDays: Int?,
    ): CandleRecollectResponse {
        validateCode(code)
        val intervalList =
            intervals.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val range = parseRecollectRange(from, to, paddingTradingDays)
        val minuteRange = parseMinuteRecentRange(minuteRecentDays)
        return try {
            candleRefreshService.recollect(code, intervalList, rangeRequest = range, minuteRangeRequest = minuteRange)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("recollectCandles 오류: code={} intervals={} err={}", code, intervals, e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "캔들 재수집 중 오류가 발생했습니다")
        }
    }

    @Operation(
        summary = "캔들 초기화 후 재수집 job 시작",
        description =
            "긴 1m/합산봉 재수집을 HTTP 동기 대기 없이 수행합니다. 응답의 topic을 STOMP로 구독하면 " +
                "진행 상태를 받을 수 있고, 같은 jobId를 REST로 조회해 STOMP 누락을 보완할 수 있습니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "재수집 job 접수"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 종목코드",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/{code}/candles/recollect/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun startRecollectJob(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(
            description = "재수집 대상 interval CSV. 합산봉(3m~12h)은 1m도 함께 재수집 후 재생성",
            example = "3m",
        )
        @RequestParam(defaultValue = "") intervals: String,
        @Parameter(description = "범위 재수집 시작 시각 ISO-8601. to와 함께 지정하면 전체 삭제 대신 범위만 재수집합니다.")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "범위 재수집 종료 시각 ISO-8601")
        @RequestParam(required = false) to: String?,
        @Parameter(description = "from/to 앞뒤 거래일 padding. 리서치 기본값은 5.")
        @RequestParam(defaultValue = "0") paddingTradingDays: Int,
        @Parameter(
            description =
                "ADR 0120 §6 재수집 모달 — 1m 기준봉(+합산 파생)만 최근 N 거래일로 제한. " +
                    "일봉 패밀리(1d·1wk·1mo·1y)는 전기간 강제 재수집 유지. from/to(리서치 range) 지정 시 무시됨.",
        )
        @RequestParam(required = false) minuteRecentDays: Int?,
    ): CandleRecollectJobResponse {
        validateCode(code)
        val intervalList = intervals.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (intervalList.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "재수집할 interval을 지정해 주세요")
        }
        return candleRecollectJobService.submitRecollect(
            code = code,
            intervals = intervalList,
            rangeRequest = parseRecollectRange(from, to, paddingTradingDays),
            minuteRangeRequest = parseMinuteRecentRange(minuteRecentDays),
        )
    }

    @Operation(
        summary = "캔들 재수집 job 상태 조회",
        description = "STOMP progress 누락 시 같은 jobId의 마지막 상태를 조회합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "job 상태 조회 성공"),
        ApiResponse(
            responseCode = "404",
            description = "job 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/candles/recollect/jobs/{jobId}")
    fun getRecollectJob(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "재수집 job id")
        @PathVariable jobId: String,
    ): CandleRecollectProgressEvent {
        validateCode(code)
        return candleRecollectJobService.find(code, jobId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "캔들 재수집 job을 찾을 수 없습니다: $jobId")
    }

    @Operation(
        summary = "종목 상세 실시간 구독 시작",
        description =
            "SymbolDetail 페이지 진입 시 호출 — watchlist 등록 여부와 무관하게 KIS WS 현재가(H0UNCNT0)·호가(H0UNASP0)와 " +
                "진행 중 캔들 close를 요청합니다. Kafka symbol.detail.command.v1 {action:ENTER} 발행 → consumer가 " +
                "DETAIL owner 실시간 가격 + 호가 구독을 처리합니다. STOMP /topic/market.tick.{code}로 push 시작. " +
                "호가(H0UNASP0)는 종목 상세를 표시 중인 동안만 구독되고 이탈 즉시 해제됩니다(2026-06-12 — anchor `detail-orderbook-live-only`).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/realtime/subscribe")
    fun subscribeRealtime(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): RealtimeSubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "ENTER")
        // ENTER 명령으로 현재가(H0UNCNT0)·호가(H0UNASP0) DETAIL 구독을 consumer가 표시 중에만 수립한다
        // (anchor `detail-orderbook-live-only`). orderbookSlot=live — frontend 호가창 복원 계약.
        return RealtimeSubscribeResponse(subscribed = true, priceSlot = "live", orderbookSlot = "live")
    }

    @Operation(
        summary = "종목 상세 실시간 구독 해제",
        description =
            "SymbolDetail 페이지 이탈 시 호출 — DETAIL owner 현재가·호가 수요를 회수합니다. " +
                "호가는 이탈 즉시 해제되고(표시 중에만 — anchor `detail-orderbook-live-only`), " +
                "해당 종목이 watchlist·strategy owner로도 요구 중이면 현재가/호가 WS 구독은 유지됩니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 해제 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/realtime/unsubscribe")
    fun unsubscribeRealtime(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): RealtimeUnsubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "EXIT")
        return RealtimeUnsubscribeResponse(unsubscribed = true)
    }

    /**
     * @deprecated 종목 상세 진입은 `/realtime/subscribe` 단일 endpoint가 현재가+호가를 함께 다룬다
     *   (2026-06-12, anchor `detail-orderbook-live-only`). 본 alias는 그와 동일한 ENTER 명령을 발행한다
     *   (과거 클라이언트 호환 — 호가는 표시 중에만 구독·이탈 즉시 해제). 삭제는 후속 정리 라운드로 보류.
     */
    @Deprecated("종목 상세 진입은 realtime/subscribe 단일 endpoint가 현재가+호가를 함께 다룬다")
    @Operation(
        summary = "호가 구독 시작 (deprecated 호환 alias)",
        description =
            "⚠ deprecated — 종목 상세 호가는 /realtime/subscribe가 현재가와 함께 다룹니다(2026-06-12). " +
                "본 alias는 동일한 ENTER 명령을 발행합니다(현재가+호가 표시 중에만 구독). " +
                "신규 클라이언트는 /realtime/subscribe를 사용하세요.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/orderbook/subscribe")
    fun subscribeOrderbook(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): OrderbookSubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "ENTER")
        // slot=live — ENTER가 현재가+호가 DETAIL 구독을 표시 중에만 수립(anchor `detail-orderbook-live-only`).
        return OrderbookSubscribeResponse(subscribed = true, slot = "live")
    }

    /**
     * @deprecated 종목 상세 이탈은 `/realtime/unsubscribe` 단일 endpoint가 현재가+호가를 함께 회수한다
     *   (2026-06-12, anchor `detail-orderbook-live-only`). 본 alias는 동일한 EXIT 명령을 발행한다
     *   (과거 클라이언트 호환). 삭제는 후속 정리 라운드로 보류.
     */
    @Deprecated("종목 상세 이탈은 realtime/unsubscribe 단일 endpoint가 현재가+호가를 함께 회수한다")
    @Operation(
        summary = "호가 구독 해제 (deprecated 호환 alias)",
        description =
            "⚠ deprecated — 종목 상세 호가는 /realtime/unsubscribe가 현재가와 함께 회수합니다(2026-06-12). " +
                "본 alias는 동일한 EXIT 명령을 발행합니다. 신규 클라이언트는 /realtime/unsubscribe를 사용하세요.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "구독 해제 명령 발행 성공"),
        ApiResponse(responseCode = "401", description = "셋업 미완"),
    )
    @PostMapping("/{code}/orderbook/unsubscribe")
    fun unsubscribeOrderbook(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
    ): OrderbookUnsubscribeResponse {
        validateCode(code)
        publishSymbolDetailCommand(code, "EXIT")
        return OrderbookUnsubscribeResponse(unsubscribed = true)
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private fun publishSymbolDetailCommand(
        code: String,
        action: String,
    ) {
        val userId =
            try {
                userAccountJpaRepository.findAll().firstOrNull()?.userId ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "action" to action,
                    "code" to code,
                    "userId" to userId,
                    "ts" to Instant.now().toString(),
                ),
            )
        kafkaTemplate.send(KafkaTopics.System.SYMBOL_DETAIL_COMMAND, code, payload)
        log.debug("symbol.detail.command 발행: action={} code={}", action, code)
    }

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
    }

    private fun parseRecollectRange(
        from: String?,
        to: String?,
        paddingTradingDays: Int,
    ): CandleRecollectRangeRequest? {
        if (from.isNullOrBlank() && to.isNullOrBlank()) return null
        if (from.isNullOrBlank() || to.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "범위 재수집은 from과 to를 함께 지정해야 합니다")
        }
        val parsedFrom =
            runCatching { Instant.parse(from) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from 시각 형식이 올바르지 않습니다: $from") }
        val parsedTo =
            runCatching { Instant.parse(to) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "to 시각 형식이 올바르지 않습니다: $to") }
        if (parsedFrom.isAfter(parsedTo)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from은 to보다 이전이어야 합니다")
        }
        return CandleRecollectRangeRequest(
            from = parsedFrom,
            to = parsedTo,
            paddingTradingDays = paddingTradingDays.coerceIn(0, 20),
        )
    }

    /**
     * ADR 0120 §6 재수집 모달 — "최근 N 거래일" → 1m 전용 [CandleRecollectRangeRequest] 변환.
     *
     * from = now - (calendar days ≈ N 거래일 + 주말 여유), to = now. 캘린더 ~N*1.5일을 잡아 거래일 N 을 충분히 커버.
     * (정밀 거래일 보정은 service layer 의 paddingTradingDays/warmup 이 추가로 처리.)
     * `minuteRecentDays` 미지정(null·0 이하)이면 null → 1m 전기간 재수집(회귀 안전).
     */
    private fun parseMinuteRecentRange(minuteRecentDays: Int?): CandleRecollectRangeRequest? {
        if (minuteRecentDays == null || minuteRecentDays <= 0) return null
        val now = Instant.now()
        // 거래일 N → 캘린더 일수 근사 (주말·휴장 여유 *1.5 + 2일). service warmup 이 부족분을 보강.
        val calendarDays = (minuteRecentDays.toLong() * 3L / 2L) + 2L
        val from = now.minus(java.time.Duration.ofDays(calendarDays))
        return CandleRecollectRangeRequest(from = from, to = now, paddingTradingDays = 0)
    }
}
