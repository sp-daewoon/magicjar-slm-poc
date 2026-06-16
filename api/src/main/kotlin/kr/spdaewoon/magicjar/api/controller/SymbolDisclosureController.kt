package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.SymbolDisclosureService
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
 * 종목별 DART 공시 REST 컨트롤러 (T-NEWS Phase 1 — ADR 0075 §5).
 *
 * - GET /api/symbols/{code}/disclosures — 공시 리스트 (페이징 + 기간 필터 + 정정공시 필터)
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern.md 정합).
 * OnboardingGuardFilter 통과 의무 (사용자 1인 baseline — 자동 적용).
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 + 룰 5 정합)
 */
@Tag(name = "Disclosure", description = "종목별 DART 공시 REST — T-NEWS Phase 1 (ADR 0075)")
@RestController
@RequestMapping("/api/symbols")
class SymbolDisclosureController(
    private val symbolDisclosureService: SymbolDisclosureService,
) {
    @Operation(
        summary = "종목별 공시 리스트 조회",
        description =
            "종목코드(6자리)로 DART 공시 목록을 페이징 조회합니다. " +
                "from/to 기간 필터(기본값: T-7일 ~ now), onlyCorrection=true 시 정정공시만 반환. " +
                "is_correction=true 항목은 UI에서 빨간 배지 강조 권장. " +
                "최대 조회 기간: 90일. 공시 없을 경우 items: [] + totalElements: 0 (mock 합성 0). " +
                "DART 수집 주기: 1분 cron (ADR 0075 §1-3 의제 2-1 채택 — 사고 인지 최대 2분).",
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
    @GetMapping("/{code}/disclosures")
    fun getDisclosures(
        @Parameter(description = "KIS 종목코드 6자리", example = "005930")
        @PathVariable code: String,
        @Parameter(description = "조회 시작일 (YYYY-MM-DD, KST 자정 기준, 미지정 시 T-7일)", example = "2026-05-08")
        @RequestParam(required = false) from: LocalDate?,
        @Parameter(description = "조회 종료일 (YYYY-MM-DD, KST 23:59:59, 미지정 시 today)", example = "2026-05-15")
        @RequestParam(required = false) to: LocalDate?,
        @Parameter(description = "정정공시만 반환 여부 (default false)", example = "false")
        @RequestParam(defaultValue = "false") onlyCorrection: Boolean,
        @Parameter(description = "페이지 번호 (0-based, default 0)", example = "0")
        @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기 (1~100, default 20)", example = "20")
        @RequestParam(defaultValue = "20") size: Int,
    ): SymbolDisclosureListResponse {
        validateCode(code)
        validatePaging(page, size)

        // hotfix 2026-05-15: frontend LocalDate(YYYY-MM-DD) 전송 → KST OffsetDateTime 변환
        val kst = ZoneId.of("Asia/Seoul")
        val fromOdt: OffsetDateTime? = from?.atStartOfDay(kst)?.toOffsetDateTime()
        val toOdt: OffsetDateTime? = to?.atTime(23, 59, 59)?.atZone(kst)?.toOffsetDateTime()

        return try {
            symbolDisclosureService.getDisclosures(
                stockCode = code,
                from = fromOdt,
                to = toOdt,
                onlyCorrection = onlyCorrection,
                page = page,
                size = size,
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "공시 데이터 조회 중 오류가 발생했습니다",
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
 * 종목별 공시 리스트 응답 DTO (ADR 0075 §5-3 LOCK).
 */
@Schema(description = "종목별 공시 리스트 응답")
data class SymbolDisclosureListResponse(
    @Schema(description = "종목코드 6자리", example = "005930")
    val stockCode: String,
    @Schema(description = "공시 항목 리스트")
    val items: List<DisclosureItem>,
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
 * 공시 단건 DTO (ADR 0075 §5-3 LOCK).
 */
@Schema(description = "공시 단건")
data class DisclosureItem(
    @Schema(description = "내부 ID", example = "1")
    val id: Long,
    @Schema(description = "DART 접수번호 (멱등성 KEY)", example = "20260515000123")
    val rceptNo: String,
    @Schema(description = "DART corp_code 8자리", example = "00126380")
    val corpCode: String,
    @Schema(description = "회사명", example = "삼성전자")
    val corpName: String,
    @Schema(description = "보고서명 (정정공시 시 [기재정정] prefix)", example = "[기재정정]사업보고서")
    val reportNm: String,
    @Schema(description = "공시제출인명 (nullable)", example = "삼성전자")
    val flrNm: String?,
    @Schema(description = "비고 — 정정·연결·첨부 등 (nullable)", example = "정")
    val rm: String?,
    @Schema(description = "정정공시 여부 — true 시 UI 빨간 배지 강조", example = "false")
    val isCorrection: Boolean,
    @Schema(description = "DART 공시 본문 URL", example = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515000123")
    val url: String,
    @Schema(description = "접수일자 YYYYMMDD (DART raw)", example = "20260515")
    val rceptDt: String,
    @Schema(description = "수집 시각 (KST)", example = "2026-05-15T09:31:21+09:00")
    val ingestedAt: java.time.OffsetDateTime,
)
