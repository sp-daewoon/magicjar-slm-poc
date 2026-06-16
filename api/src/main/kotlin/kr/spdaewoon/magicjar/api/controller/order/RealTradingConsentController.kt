package kr.spdaewoon.magicjar.api.controller.order

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.service.order.RealTradingConsentService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 실거래 동의 토글 REST 컨트롤러 (ADR 0068 §15-3 PUT consent — 의제 A-1 3중 게이트 + Swagger 4단 한국어).
 *
 * SettingsModal "실거래 모드 동의" 토글 ON/OFF → user_account.real_trading_consent_at 갱신.
 * ON 시 경고: 실 자본 변동 (3중 게이트 3번째 조건).
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Tag(name = "사용자 계정", description = "사용자 계정 설정 API — 실거래 동의 토글 포함")
@RestController
@RequestMapping("/api/user-account")
class RealTradingConsentController(
    private val consentService: RealTradingConsentService,
) {
    private val log = LoggerFactory.getLogger(RealTradingConsentController::class.java)

    // ─── PUT /api/user-account/real-trading-consent ───────────────────────────

    @Operation(
        summary = "실거래 모드 동의 토글",
        description =
            "SettingsModal 실거래 모드 동의 토글. " +
                "consented=true → user_account.real_trading_consent_at=NOW() (3중 게이트 조건 3 충족). " +
                "consented=false → NULL (REAL 주문 BLOCKED). " +
                "주의: 실거래 모드 활성화를 위해서는 환경변수 MAGICJAR_MODE=REAL + MAGICJAR_ALLOW_REAL=true 추가 필요 (ADR 0068 §14-3).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "동의 토글 성공"),
        ApiResponse(responseCode = "404", description = "사용자 없음"),
    )
    @PutMapping("/real-trading-consent")
    fun toggleConsent(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
        @RequestBody body: ConsentToggleRequestDto,
    ): ConsentToggleResponseDto {
        log.info("실거래 동의 토글 요청: userId={} consented={}", userId, body.consented)
        val consentAt = consentService.setConsent(userId, body.consented)
        return ConsentToggleResponseDto(
            userId = userId,
            consented = body.consented,
            realTradingConsentAt = consentAt?.toString(),
        )
    }

    // ─── GET /api/user-account/real-trading-consent ───────────────────────────

    @Operation(
        summary = "실거래 동의 상태 조회",
        description = "사용자의 현재 실거래 동의 상태와 동의 시각을 반환합니다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "동의 상태 조회 성공"),
    )
    @org.springframework.web.bind.annotation.GetMapping("/real-trading-consent")
    fun getConsent(
        @Parameter(description = "사용자 ID", example = "1", required = true)
        @RequestParam userId: String,
    ): ConsentToggleResponseDto {
        val consentAt = consentService.getConsentAt(userId)
        return ConsentToggleResponseDto(
            userId = userId,
            consented = consentAt != null,
            realTradingConsentAt = consentAt?.toString(),
        )
    }
}

// ─── DTO ──────────────────────────────────────────────────────────────────────

@Schema(description = "실거래 동의 토글 요청 DTO")
data class ConsentToggleRequestDto(
    @field:Schema(
        description = "동의 여부. true: 실거래 동의(NOW() 기록), false: 동의 철회(NULL)",
        example = "true",
    )
    val consented: Boolean,
)

@Schema(description = "실거래 동의 토글 응답 DTO")
data class ConsentToggleResponseDto(
    @field:Schema(description = "사용자 ID", example = "1")
    val userId: String,
    @field:Schema(description = "동의 상태", example = "true")
    val consented: Boolean,
    @field:Schema(description = "동의 시각 (ISO-8601 UTC, 미동의 시 null)", example = "2026-05-13T09:00:00Z")
    val realTradingConsentAt: String?,
)
