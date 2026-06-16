package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

// ─── 공통 에러 응답 ───────────────────────────────────────────────────────────

/**
 * 공통 에러 응답 포맷 (spec §4 dispatch-spec §4-2 정합).
 */
@Schema(description = "에러 응답")
data class ErrorResponse(
    @Schema(
        description = "에러 코드",
        example = "KIS_AUTH_FAILED",
        allowableValues = [
            "KIS_AUTH_FAILED",
            "ALREADY_REGISTERED",
            "KIS_UNAVAILABLE",
            "ONBOARDING_REQUIRED",
            "VALIDATION_ERROR",
            "SLACK_WEBHOOK_FAILED",
            "REAL_ACCOUNT_NOT_FOUND",
            "REAL_ACCOUNT_REQUIRED",
        ],
    )
    val code: String,
    @Schema(description = "한국어 에러 메시지", example = "KIS 키 검증 실패 — 콘솔에서 키 재발급")
    val message: String,
)

// ─── GET /api/onboarding/status ──────────────────────────────────────────────

/**
 * 온보딩 상태 응답 (spec §4-1-1).
 * userId: 단일 사용자 시스템 전용 — KIS 실전 계좌번호 8자리.
 *   hasUserAccount=true 시 첫 등록 row의 userId, false 시 null.
 *   frontend store auto-sync 용도. app_key/secret은 절대 포함 금지.
 */
@Schema(description = "온보딩 셋업 상태")
data class OnboardingStatus(
    @Schema(description = "실전 계좌 등록 여부", example = "false")
    val hasUserAccount: Boolean,
    @Schema(description = "모의 계좌 등록 여부", example = "false")
    val hasMockAccount: Boolean,
    @Schema(description = "DART API 키 등록 여부", example = "false")
    val hasDartKey: Boolean,
    @Schema(description = "Slack webhook 등록 여부", example = "false")
    val hasSlackWebhook: Boolean,
    @Schema(
        description = "KIS 실전 계좌번호 (userId) — 단일 사용자 시스템. hasUserAccount=false 시 null",
        example = "74683223",
        nullable = true,
    )
    val userId: String? = null,
)

// ─── POST /api/onboarding/kis/validate ───────────────────────────────────────

/**
 * KIS 실전 키 검증 요청 (spec §4-1-2).
 */
@Schema(description = "KIS 실전 키 등록·검증 요청")
data class KisValidateRequest(
    @field:Pattern(regexp = "^\\d{8}$", message = "계좌번호는 8자리 숫자여야 합니다")
    @field:NotBlank
    @Schema(description = "KIS 실전 계좌번호 8자리", example = "74683223")
    val accountNumber: String,
    @field:Size(max = 100)
    @Schema(description = "UI 닉네임 (선택)", example = "내 실전 계좌", nullable = true)
    val displayName: String? = null,
    @field:NotBlank
    @Schema(description = "KIS 콘솔 발급 APP_KEY", example = "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    val appKey: String,
    @field:NotBlank
    @Schema(description = "KIS 콘솔 발급 SECRET_KEY")
    val secretKey: String,
)

/**
 * KIS 실전 키 검증 성공 응답 (spec §4-1-2).
 */
@Schema(description = "KIS 실전 키 검증 성공 응답")
data class KisValidateResponse(
    @Schema(description = "KIS 계좌번호 (userId)", example = "74683223")
    val userId: String,
    @Schema(description = "마스킹된 계좌번호", example = "7468****")
    val maskedAccount: String,
    @Schema(description = "UI 닉네임", example = "내 실전 계좌", nullable = true)
    val displayName: String?,
    @Schema(description = "검증 상태", example = "VALIDATED")
    val status: String = "VALIDATED",
    @Schema(description = "검증 시각 (UTC ISO-8601)", example = "2026-05-07T10:23:45.123Z")
    val validatedAt: Instant,
)

// ─── POST /api/onboarding/kis/mock ───────────────────────────────────────────

/**
 * KIS 모의 계좌 추가 요청 (spec §4-1-3).
 */
@Schema(description = "KIS 모의 계좌 등록 요청")
data class KisMockRequest(
    @field:Pattern(regexp = "^\\d{8}$", message = "계좌번호는 8자리 숫자여야 합니다")
    @field:NotBlank
    @Schema(description = "실전 계좌번호 (이미 등록되어 있어야 함)", example = "74683223")
    val userId: String,
    @field:Pattern(regexp = "^\\d{8}$", message = "모의 계좌번호는 8자리 숫자여야 합니다")
    @field:NotBlank
    @Schema(description = "KIS 모의 계좌번호 8자리", example = "22233344")
    val mockAccountNumber: String,
    @field:NotBlank
    @Schema(description = "KIS 모의 APP_KEY")
    val appKey: String,
    @field:NotBlank
    @Schema(description = "KIS 모의 SECRET_KEY")
    val secretKey: String,
)

/**
 * KIS 모의 계좌 추가 성공 응답 (spec §4-1-3).
 */
@Schema(description = "KIS 모의 계좌 등록 성공 응답")
data class KisMockResponse(
    @Schema(description = "실전 계좌번호 (userId)", example = "74683223")
    val userId: String,
    @Schema(description = "모의 계좌번호", example = "22233344")
    val mockAccountNumber: String,
    @Schema(description = "검증 상태", example = "VALIDATED")
    val status: String = "VALIDATED",
)

// ─── POST /api/onboarding/system-config ──────────────────────────────────────

/**
 * 시스템 전역 설정 요청 (spec §4-1-4).
 * null = 변경 안 함, "" (empty) = 삭제, 값 있음 = 등록·갱신.
 */
@Schema(description = "시스템 전역 설정 (DART·Slack)")
data class SystemConfigRequest(
    @Schema(
        description = "DART API 키 — null: 변경 안 함, 빈 문자열: 삭제, 값: 등록·갱신",
        example = "someDART-API-key",
        nullable = true,
    )
    val dartApiKey: String? = null,
    @Schema(
        description = "Slack webhook URL — null: 변경 안 함, 빈 문자열: 삭제, 값: 등록·갱신",
        example = "https://hooks.slack.com/services/T00/B00/xxx",
        nullable = true,
    )
    val slackWebhookUrl: String? = null,
    @Schema(
        description = "fallback source candle 신규 BUY 허용 여부 — null: 변경 안 함",
        example = "true",
        nullable = true,
    )
    val realtimeFallbackBuyEnabled: Boolean? = null,
)

/**
 * 시스템 전역 설정 응답 (spec §4-1-4).
 */
@Schema(description = "시스템 전역 설정 결과")
data class SystemConfigResponse(
    @Schema(description = "DART API 키 등록 여부", example = "true")
    val hasDartKey: Boolean,
    @Schema(description = "Slack webhook 등록 여부", example = "true")
    val hasSlackWebhook: Boolean,
    @Schema(description = "DART API 키 평문 — 설정 화면 재입력 UX용", example = "someDART-API-key", nullable = true)
    val dartApiKey: String? = null,
    @Schema(
        description = "Slack webhook URL 평문 — 설정 화면 재입력 UX용",
        example = "https://hooks.slack.com/services/T00/B00/xxx",
        nullable = true,
    )
    val slackWebhookUrl: String? = null,
    @Schema(description = "fallback source candle 신규 BUY 허용 여부", example = "true")
    val realtimeFallbackBuyEnabled: Boolean = true,
    @Schema(description = "갱신 시각 (UTC ISO-8601)", example = "2026-05-07T10:23:45.123Z")
    val updatedAt: Instant,
)

// ─── POST /api/onboarding/complete ───────────────────────────────────────────

/**
 * 온보딩 완료 요청 (spec §4-1-5).
 */
@Schema(description = "온보딩 완료 요청")
data class OnboardingCompleteRequest(
    @field:NotBlank
    @Schema(description = "실전 계좌번호 (userId)", example = "74683223")
    val userId: String,
)

/**
 * 온보딩 완료 응답 (spec §4-1-5).
 */
@Schema(description = "온보딩 완료 응답")
data class OnboardingCompleteResponse(
    @Schema(description = "실전 계좌번호 (userId)", example = "74683223")
    val userId: String,
    @Schema(description = "마스킹된 계좌번호", example = "7468****")
    val maskedAccount: String,
    @Schema(description = "대시보드 리다이렉트 경로", example = "/")
    val redirectTo: String = "/",
)
