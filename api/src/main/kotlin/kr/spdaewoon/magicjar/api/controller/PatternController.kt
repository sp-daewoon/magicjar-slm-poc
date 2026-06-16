package kr.spdaewoon.magicjar.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.datetime.toKotlinInstant
import kr.spdaewoon.magicjar.api.controller.dto.PatternResponseDto
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.pattern.cache.RedisPatternCacheAdapter
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * 캔들스틱 패턴 57종 인식 REST 컨트롤러 (spec §6-4 LOCK).
 *
 * GET /api/patterns?code=...&interval=...&patterns=...&from=...&to=...
 *
 * - 57패턴 중 다중 패턴을 candle 시계열 위에서 인식 → matches 반환
 * - Redis 캐시: 분봉 5분 / 일봉 1일 TTL (T-IND 정합)
 * - 미지원 패턴 → 결과 미포함 (graceful, KIS L52 정합)
 * - R2 stub 47건 → direction=NEUTRAL → 결과 미포함 (graceful)
 * - 캔들 부족(< 5건) → 빈 matches (KIS L48 정합)
 *
 * PatternRecognizer bean: "patternRecognizer" (pattern-engine JvmPatternRecognizer — B-1 사이클 완료).
 * RedisPatternCacheAdapter: pattern-engine 단일 구현 (spec §5-3 unify — api 측 삭제).
 * 룰 5 — LLM provider import 0건.
 * ADR 0051 — 신규 JPA Repository 0건 → default fun 함정 발동 0.
 * PR-43 — @ConditionalOnProperty 임시 게이트 제거 (PR-261 hotfix 잔재 자연 정상화).
 */
@Tag(
    name = "Patterns",
    description = "캔들스틱 패턴 57종 인식 (KIS Strategy Builder candlestick.py 정합) — T-PAT W2",
)
@RestController
@RequestMapping("/api/patterns")
class PatternController(
    private val patternRecognizer: PatternRecognizer,
    private val candleJpaRepository: CandleJpaRepository,
    private val cacheAdapter: RedisPatternCacheAdapter,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(PatternController::class.java)

    @Operation(
        summary = "패턴 인식 시계열",
        description =
            "57패턴 중 다중 패턴을 candle 시계열 위에서 인식하여 매칭 결과를 반환합니다. " +
                "결과는 Redis 캐시 적용 — 분봉(1m·2m·3m·5m·15m·30m·90m·1h·4h·12h) 5분 TTL, 일봉 이상(1d·1wk·1mo·1y) 1일 TTL. " +
                "미지원 패턴 요청 시 결과 미포함(graceful, KIS 정합). " +
                "R2 stub 47건 패턴은 direction=NEUTRAL → 결과 미포함. " +
                "캔들 부족(< 5건) 시 빈 matches(graceful, KIS L48 정합).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "패턴 인식 성공 (matches=[] 포함 — 미감지도 200)"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 파라미터 — 종목코드 형식 오류 · 지원하지 않는 interval · 잘못된 시각 형식",
            content = [Content(schema = Schema(implementation = Map::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = Map::class))],
        ),
    )
    @GetMapping
    fun getPatterns(
        @Parameter(description = "종목코드 6자리", example = "005930", required = true)
        @RequestParam code: String,
        @Parameter(
            description = "캔들 interval (1m·2m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            example = "1d",
            required = true,
        )
        @RequestParam interval: String,
        @Parameter(
            description = "패턴 ID 콤마 구분 (예: hammer,engulfing,doji) — 57 catalog 중 선택",
            example = "hammer,engulfing",
            required = true,
        )
        @RequestParam patterns: String,
        @Parameter(description = "조회 시작 시각 (ISO 8601 UTC, 예: 2024-01-01T00:00:00Z)")
        @RequestParam(required = false) from: String?,
        @Parameter(description = "조회 종료 시각 (ISO 8601 UTC, 예: 2024-12-31T23:59:59Z)")
        @RequestParam(required = false) to: String?,
    ): PatternResponseDto {
        validateCode(code)
        val patternSet = parsePatterns(patterns)

        // 1. Redis 캐시 hit 확인
        val cached = cacheAdapter.get(code, interval, patternSet, from, to)
        if (cached != null) {
            return objectMapper.readValue(cached, PatternResponseDto::class.java)
        }

        // 2. DB candle 조회
        val candles = loadCandles(code, interval, from, to)

        // 3. 패턴 인식 (candles < 5이면 JvmPatternRecognizer에서 빈 리스트 — KIS L48)
        val matches = patternRecognizer.recognize(candles, patternSet)

        // 4. 응답 DTO 변환
        val response =
            PatternResponseDto(
                matches =
                    matches.map { m ->
                        PatternResponseDto.PatternMatchDto(
                            pattern = m.pattern,
                            startTime = m.startTime.toString(),
                            endTime = m.endTime.toString(),
                            direction = m.direction.name,
                            confidence = m.confidence.toPlainString(),
                            params = m.params?.mapValues { it.value.toPlainString() },
                        )
                    },
            )

        // 5. Redis 캐시 저장
        cacheAdapter.put(code, interval, patternSet, from, to, objectMapper.writeValueAsString(response))

        return response
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "종목코드는 6자리 숫자여야 합니다 (예: 005930)")
        }
    }

    private fun parsePatterns(patterns: String): Set<String> {
        if (patterns.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "patterns 파라미터는 필수입니다 (예: hammer,engulfing)")
        }
        return patterns
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * DB candle 조회 → Candle 도메인 객체 변환.
     * IndicatorService.loadCandles 패턴 정합 (ADR 0051 — Repository body fun 0건 준수).
     */
    private fun loadCandles(
        code: String,
        interval: String,
        from: String?,
        to: String?,
    ): List<Candle> {
        val intervalEnum =
            try {
                CandleInterval.fromCode(interval)
            } catch (e: IllegalArgumentException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 interval: $interval")
            }

        val fromInstant: Instant? = from?.let { parseJavaInstant(it) }
        val toInstant: Instant? = to?.let { parseJavaInstant(it) }

        val rawEntities =
            if (toInstant != null) {
                candleJpaRepository.findByCodeAndIntervalAndTsBefore(
                    code,
                    interval,
                    toInstant,
                    PageRequest.of(0, 1000),
                )
            } else {
                candleJpaRepository.findByCodeAndIntervalLatest(
                    code,
                    interval,
                    PageRequest.of(0, 500),
                )
            }

        val filtered =
            if (fromInstant != null) {
                rawEntities.filter { !it.ts.isBefore(fromInstant) }
            } else {
                rawEntities
            }

        return filtered
            .map { entity ->
                Candle(
                    id = entity.id,
                    code = entity.code,
                    interval = intervalEnum,
                    ts = entity.ts.toKotlinInstant(),
                    openRaw = entity.openRaw ?: BigDecimal.ZERO,
                    highRaw = entity.highRaw ?: BigDecimal.ZERO,
                    lowRaw = entity.lowRaw ?: BigDecimal.ZERO,
                    closeRaw = entity.closeRaw ?: BigDecimal.ZERO,
                    openAdj = entity.openAdj,
                    highAdj = entity.highAdj,
                    lowAdj = entity.lowAdj,
                    closeAdj = entity.closeAdj,
                    volume = entity.volume ?: 0L,
                    source = entity.source,
                )
            }.sortedBy { it.ts }
    }

    private fun parseJavaInstant(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "잘못된 시각 형식: $value (ISO 8601 UTC 필요, 예: 2024-01-01T00:00:00Z)",
            )
        }
}
