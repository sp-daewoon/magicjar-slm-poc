package kr.spdaewoon.magicjar.api.controller.account

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.controller.ErrorResponse
import kr.spdaewoon.magicjar.api.service.account.AccountQueryService
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCircuitOpenException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException

/**
 * 계좌 잔고·예수금·보유 종목 사용자 노출 컨트롤러 — T-ACCT Phase 2 (ADR 0081 §2-1).
 *
 * ### 엔드포인트
 * - `GET /api/account/balance?userId=...&accountType=REAL`   → [AccountBalanceResponse]
 * - `GET /api/account/positions?userId=...&accountType=REAL` → `List<[AccountHoldingResponse]>`
 * - `GET /api/account/summary?userId=...&accountType=REAL`   → [AccountSummaryResponse]
 *
 * 모두 동일 KIS `inquire-balance` 호출 결과를 Redis cache `account:balance:{userId}:{accountType}` TTL 5분에 공유.
 *
 * ### Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합)
 * - 컨트롤러: @Tag
 * - 메서드: @Operation·@ApiResponses
 * - 파라미터: @Parameter
 * - DTO: @Schema (AccountDto.kt)
 *
 * ### mock 합성 0 LOCK (feedback_no_mock_data)
 * - KIS upstream 실패 → 502 BAD_GATEWAY
 * - 자격증명 부재 → 412 PRECONDITION_FAILED
 * - 0 값 합성 응답 0 (frontend "데이터 없음" 한국어 표시는 frontend 영역)
 *
 * ### AI 호출 0 LOCK (룰 5)
 * - LLM provider import 0
 *
 * ### KIS-first raw fetch 인용 (헌법 §3 LOCK · spec §3-1)
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 * - https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/chk_inquire_balance.py
 */
@Tag(
    name = "Account",
    description = "계좌 잔고·예수금·보유 종목 조회 — T-ACCT (ADR 0081 + KIS inquire-balance)",
)
@RestController
@RequestMapping("/api/account")
class AccountController(
    private val accountQueryService: AccountQueryService,
) {
    private val log = LoggerFactory.getLogger(AccountController::class.java)

    // ── GET /api/account/balance ──────────────────────────────────────────────

    @Operation(
        summary = "계좌 잔고(예수금·평가금·평가손익) 조회",
        description =
            "KIS inquire-balance output2 11 필드를 반환합니다 (예수금 D+1·D+2·총 평가금액·순자산·매입합계·평가합계·평가손익 등). " +
                "Redis cache `account:balance:{userId}:{accountType}` TTL 5분 — 주문 이벤트 발생 시 backend가 즉시 무효화합니다. " +
                "사용자 메모리 feedback_kis_key_realonly_for_data 정합 — 정보 수집은 모두 실전 키 우선. " +
                "mock 합성 0 LOCK — KIS 실패 시 502 발행.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "잔고 조회 성공"),
        ApiResponse(
            responseCode = "412",
            description = "KIS 자격증명 부재 (사용자가 SettingsModal에서 등록 필요)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "KIS upstream 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/balance")
    fun balance(
        @Parameter(description = "사용자 ID", example = "user_001", required = true)
        @RequestParam userId: String,
        @Parameter(
            description = "계좌 유형 (REAL=실전 / MOCK=모의) — 기본 REAL",
            example = "REAL",
        )
        @RequestParam(required = false, defaultValue = "REAL") accountType: String,
        @Parameter(description = "true면 Redis cache를 우회하고 KIS 잔고를 즉시 재조회", example = "false")
        @RequestParam(required = false, defaultValue = "false") refresh: Boolean,
    ): AccountBalanceResponse {
        val type = parseAccountType(accountType)
        log.debug("account balance 조회: userId={} type={} refresh={}", maskUserId(userId), type.name, refresh)
        return runKisCall { accountQueryService.getBalance(userId, type, refresh) }
    }

    // ── GET /api/account/positions ────────────────────────────────────────────

    @Operation(
        summary = "보유 종목 목록 조회 (KIS 실시간)",
        description =
            "KIS inquire-balance output1 — 보유 종목별 평균가·현재가·평가금·손익을 반환합니다. " +
                "DB `position` 테이블 조회(`/api/positions`)와 달리 KIS 실시간 시세 포함. " +
                "동일 Redis cache `account:balance:{userId}:{accountType}` TTL 5분 재사용 (KIS 호출 0 추가).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "보유 종목 조회 성공 (0건이면 빈 배열)"),
        ApiResponse(
            responseCode = "412",
            description = "KIS 자격증명 부재",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "KIS upstream 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/positions")
    fun positions(
        @Parameter(description = "사용자 ID", example = "user_001", required = true)
        @RequestParam userId: String,
        @Parameter(description = "계좌 유형 (REAL/MOCK) — 기본 REAL", example = "REAL")
        @RequestParam(required = false, defaultValue = "REAL") accountType: String,
        @Parameter(description = "true면 Redis cache를 우회하고 KIS 잔고를 즉시 재조회", example = "false")
        @RequestParam(required = false, defaultValue = "false") refresh: Boolean,
    ): List<AccountHoldingResponse> {
        val type = parseAccountType(accountType)
        log.debug("account positions 조회: userId={} type={} refresh={}", maskUserId(userId), type.name, refresh)
        return runKisCall { accountQueryService.getPositions(userId, type, refresh) }
    }

    // ── GET /api/account/summary ──────────────────────────────────────────────

    @Operation(
        summary = "계좌 요약 조회 (Dashboard 헤더용)",
        description =
            "총 평가금액 + 예수금 D+2 + 평가손익 + 수익률 + 보유 종목 수 5 필드 + 조회 시각. " +
                "Dashboard `pages/Dashboard/AccountSummaryCard.tsx`에서 30s polling 으로 사용.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "요약 조회 성공"),
        ApiResponse(
            responseCode = "412",
            description = "KIS 자격증명 부재",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "502",
            description = "KIS upstream 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/summary")
    fun summary(
        @Parameter(description = "사용자 ID", example = "user_001", required = true)
        @RequestParam userId: String,
        @Parameter(description = "계좌 유형 (REAL/MOCK) — 기본 REAL", example = "REAL")
        @RequestParam(required = false, defaultValue = "REAL") accountType: String,
        @Parameter(description = "true면 Redis cache를 우회하고 KIS 잔고를 즉시 재조회", example = "false")
        @RequestParam(required = false, defaultValue = "false") refresh: Boolean,
    ): AccountSummaryResponse {
        val type = parseAccountType(accountType)
        log.debug("account summary 조회: userId={} type={} refresh={}", maskUserId(userId), type.name, refresh)
        return runKisCall { accountQueryService.getSummary(userId, type, refresh) }
    }

    // ── private helper ────────────────────────────────────────────────────────

    private fun parseAccountType(raw: String): AccountType =
        runCatching { AccountType.valueOf(raw.trim().uppercase()) }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "accountType은 REAL 또는 MOCK이어야 합니다 (입력: $raw)",
                )
            }

    /**
     * KIS 호출 예외를 HTTP 상태 코드로 변환 (spec §4-4 매트릭스 LOCK).
     *
     * - 자격증명 부재 (IllegalStateException) → 412 PRECONDITION_FAILED
     * - KIS rate limiter timeout → 429 TOO_MANY_REQUESTS
     * - KIS rate limiter circuit open → 503 SERVICE_UNAVAILABLE
     * - KIS upstream 실패 (WebClientResponseException) → 502 BAD_GATEWAY
     * - 기타 → 500 (Spring default ResponseStatusException 변환)
     */
    private inline fun <T> runKisCall(block: () -> T): T =
        try {
            block()
        } catch (ex: IllegalStateException) {
            log.warn("account KIS 자격증명 부재: msg={}", ex.message)
            throw ResponseStatusException(
                HttpStatus.PRECONDITION_FAILED,
                ex.message ?: "KIS 자격증명 부재",
            )
        } catch (ex: KisRateLimitTimeoutException) {
            log.warn(
                "account KIS local rate limit timeout: priority={} timeoutMillis={}",
                ex.priority,
                ex.timeoutMillis,
            )
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "KIS 호출량이 많아 잠시 후 다시 시도해주세요.",
            )
        } catch (ex: KisCircuitOpenException) {
            log.warn("account KIS rate limit circuit open: openUntil={}", ex.openUntilEpochMillis)
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "KIS 호출 제한 회로가 열려 잠시 후 다시 시도해주세요.",
            )
        } catch (ex: WebClientResponseException) {
            val body = ex.responseBodyAsString
            if (classifyKisError(ex.statusCode.value(), body) is KisErrorClass.RateLimit) {
                log.warn("account KIS rate limit: status={} body={}", ex.statusCode, body.take(200))
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "KIS 호출량이 많아 잠시 후 다시 시도해주세요.",
                )
            }
            log.error("account KIS upstream 실패: status={} body={}", ex.statusCode, body.take(200))
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "KIS upstream 실패 (status=${ex.statusCode.value()})",
            )
        }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"
}
