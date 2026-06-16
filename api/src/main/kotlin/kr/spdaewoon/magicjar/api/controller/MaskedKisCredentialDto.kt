package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

// ADR 0074 §2-2-2 정합 — KIS 자격증명 마스킹 응답 DTO + 변경 요청 DTO

// ─── GET /api/onboarding/kis/credentials 응답 ────────────────────────────────

/**
 * KIS 자격증명 마스킹 응답 — 전체 목록 (REAL + MOCK).
 * 평문 appKey·secretKey 절대 미포함 (ADR 0046 §2-5 + 메모리 feedback_secret_handling.md).
 */
@Schema(description = "KIS 자격증명 마스킹 응답 목록")
data class MaskedKisCredentialResponseDto(
    @Schema(description = "실전 계좌번호 (userId)", example = "74683223")
    val userId: String,
    @Schema(description = "등록된 KIS 자격증명 목록 (REAL + MOCK)")
    val credentials: List<MaskedKisCredentialItem>,
)

/**
 * 개별 KIS 자격증명 항목 — 마스킹 표시.
 */
@Schema(description = "KIS 자격증명 항목 (마스킹)")
data class MaskedKisCredentialItem(
    @Schema(
        description = "계좌 유형 — REAL(실전) 또는 MOCK(모의)",
        example = "REAL",
        allowableValues = ["REAL", "MOCK"],
    )
    val accountType: String,
    @Schema(description = "마스킹된 계좌번호 (앞 4자 + ****)", example = "7468****")
    val accountNumber: String,
    @Schema(description = "마스킹된 APP_KEY (앞 4자 + *** + 뒤 4자)", example = "PS01***wxyz")
    val appKeyMasked: String,
    @Schema(description = "마스킹된 SECRET_KEY (전부 *)", example = "********")
    val secretKeyMasked: String,
    @Schema(description = "마지막 KIS 검증 시각 (UTC ISO-8601). row 없으면 null", example = "2026-05-14T09:31:21.000Z", nullable = true)
    val lastValidatedAt: Instant?,
    @Schema(description = "등록 여부 — false이면 row 미등록 (등록된 키 없음)", example = "true")
    val hasKey: Boolean,
)

// ─── PUT /api/onboarding/kis/{accountType} 요청 ──────────────────────────────

/**
 * KIS 자격증명 변경 요청 — SettingsModal KIS 키 변경 전용 (ADR 0074 §2-2-1).
 * 계좌번호(accountNumber) 변경 불가 — read-only (path variable accountType만 지정).
 * 첫 등록은 POST /api/onboarding/kis/validate 사용 (SetupWizard 전용).
 */
@Schema(description = "KIS 자격증명 변경 요청 — 이미 등록된 키 변경 전용 (SetupWizard 사용 불가)")
data class KisCredentialUpdateRequest(
    @field:NotBlank
    @field:Size(min = 10, max = 100)
    @Schema(
        description = "KIS 콘솔 발급 APP_KEY — 새 키 입력",
        example = "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    )
    val appKey: String,
    @field:NotBlank
    @field:Size(min = 10, max = 200)
    @Schema(
        description = "KIS 콘솔 발급 SECRET_KEY — 새 키 입력",
        example = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy",
    )
    val secretKey: String,
)

// ─── PUT /api/onboarding/kis/{accountType} 응답 (MaskedKisCredentialItem reuse) ──
// 응답은 MaskedKisCredentialItem 단건을 그대로 사용

// ─── DELETE /api/onboarding/kis/{accountType} 응답 ───────────────────────────

/**
 * KIS 자격증명 삭제 응답 — MOCK만 허용 (ADR 0074 §2-2-1 §h 채택).
 */
@Schema(description = "KIS 자격증명 삭제 응답")
data class KisCredentialDeleteResponse(
    @Schema(description = "삭제된 계좌 유형", example = "MOCK")
    val accountType: String,
    @Schema(description = "삭제 완료 메시지", example = "KIS 모의 계좌 자격증명이 삭제되었습니다")
    val message: String = "KIS 모의 계좌 자격증명이 삭제되었습니다",
)
