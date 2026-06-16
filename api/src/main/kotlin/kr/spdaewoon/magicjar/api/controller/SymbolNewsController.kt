package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.SymbolNewsService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 종목별 Naver 뉴스 REST 컨트롤러 (T-NEWS Phase 2 — ADR 0075 §5).
 *
 * - GET /api/symbols/{code}/news — 뉴스 리스트 (페이징 + 기간 필터)
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern.md 정합):
 *   @Tag(컨트롤러) + @Operation(메서드) + @Parameter(파라미터) + @Schema(DTO) 4단
 *   한국어 description + example 값 + 200/400/404/500 ApiResponse 매트릭스
 *
 * OnboardingGuardFilter 통과 의무 (사용자 1인 baseline — 자동 적용).
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 *   뉴스 없을 경우 items: [] + totalElements: 0 반환 (에러 0)
 *   Naver 키 미등록 시 items: [] 그대로 반환 (graceful)
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 + 룰 5 정합)
 */
@Tag(name = "News", description = "종목별 Naver 뉴스 REST — T-NEWS Phase 2 (ADR 0075)")
@RestController
@RequestMapping("/api/symbols")
class SymbolNewsController(
    private val symbolNewsService: SymbolNewsService,
) {
    @Operation(
        summary = "종목별 뉴스 리스트 조회",
        description =
            "종목코드(6자리)로 Naver Open API 수집 뉴스 목록을 페이징 조회합니다. " +
                "from/to 기간 필터(기본값: T-7일 ~ now). " +
                "뉴스 없을 경우 items: [] + totalElements: 0 반환 (mock 합성 0). " +
                "Naver API 키 미등록 시에도 동일하게 items: [] 반환. " +
                "최대 조회 기간: 90일. 수집 주기: 1분 cron Watchlist 종목 우선 (분당 17건 한도 — ADR 0075 §4-2).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 파라미터 (종목코드 형식 오류·기간 범위 초과·from > to)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "종목 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "셋업 미완 — OnboardingGuardFilter 차단",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/{code}/news")
    fun getNews(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "조회 시작일 (YYYY-MM-DD, KST 자정, 미지정 시 T-7일)", example = "2026-05-08")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "조회 종료일 (YYYY-MM-DD, KST 23:59:59, 미지정 시 today)", example = "2026-05-15")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "페이지 번호 (0-based, default 0)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기 (1~100, default 20)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): SymbolNewsListResponse {
        validateCode(code)
        validatePaging(page, size)

        // hotfix 2026-05-15: frontend LocalDate(YYYY-MM-DD) 전송 → KST OffsetDateTime 변환
        val kst = ZoneId.of("Asia/Seoul")
        val fromOdt: OffsetDateTime? = from?.atStartOfDay(kst)?.toOffsetDateTime()
        val toOdt: OffsetDateTime? = to?.atTime(23, 59, 59)?.atZone(kst)?.toOffsetDateTime()

        return try {
            symbolNewsService.getNews(
                stockCode = code,
                from = fromOdt,
                to = toOdt,
                page = page,
                size = size,
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "뉴스 데이터 조회 중 오류가 발생했습니다",
            )
        }
    }

    // ── private validators ───────────────────────────────────────────────────

    private fun validateCode(code: String) {
        if (!code.matches(Regex("^\\d{6}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "종목 코드는 6자리 숫자여야 합니다 (예: 005930)",
            )
        }
    }

    private fun validatePaging(
        page: Int,
        size: Int,
    ) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다")
        }
        if (size < 1 || size > 100) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1~100 범위여야 합니다")
        }
    }
}

// ── 응답 DTO ───────────────────────────────────────────────────────────────────

/**
 * 종목별 뉴스 리스트 응답 DTO (ADR 0075 §5-3 LOCK).
 */
@Schema(description = "종목별 뉴스 리스트 응답")
data class SymbolNewsListResponse(
    @Schema(description = "종목코드 6자리", example = "005930")
    val stockCode: String,
    @Schema(description = "뉴스 항목 리스트")
    val items: List<NewsItem>,
    @Schema(description = "현재 페이지 번호 (0-based)", example = "0")
    val page: Int,
    @Schema(description = "페이지 크기", example = "20")
    val size: Int,
    @Schema(description = "전체 항목 수", example = "42")
    val totalElements: Long,
    @Schema(description = "전체 페이지 수", example = "3")
    val totalPages: Int,
)

/**
 * 뉴스 단건 DTO (ADR 0075 §5-3 LOCK).
 */
@Schema(description = "뉴스 단건")
data class NewsItem(
    @Schema(description = "내부 ID", example = "1")
    val id: Long,
    @Schema(description = "뉴스 출처 (NAVER | RSS_HANKYUNG — Phase 4)", example = "NAVER")
    val source: String,
    @Schema(description = "뉴스 제목 (<b> 태그 제거 후 영속)", example = "삼성전자, 메모리 반도체 가격 인상 검토")
    val title: String,
    @Schema(description = "뉴스 요약 (<b> 태그 제거 후 영속, nullable)", example = "삼성전자가 메모리 반도체 가격 인상을 검토 중인 것으로...")
    val bodySummary: String?,
    @Schema(description = "뉴스 원문 URL", example = "https://n.news.naver.com/article/001/0012345678")
    val url: String,
    @Schema(description = "발행 시각 (KST)", example = "2026-05-15T08:50:00+09:00")
    val publishedAt: java.time.OffsetDateTime,
    @Schema(description = "감성 분석 결과 (T-AI 흡수 backlog §62 — 1차 NULL)", example = "null")
    val sentiment: String?,
)

// ErrorResponse — OnboardingDto.kt에 정의된 공용 DTO reuse (중복 선언 0)
