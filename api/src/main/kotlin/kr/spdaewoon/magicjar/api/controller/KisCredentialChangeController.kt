package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.KisCredentialChangeService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// ADR 0074 §2-2 + spec §2-2 정합
// 3 endpoint:
//   GET    /api/onboarding/kis/credentials              — 마스킹 응답
//   PUT    /api/onboarding/kis/{accountType}            — 키 변경 (REAL/MOCK)
//   DELETE /api/onboarding/kis/{accountType}            — 키 삭제 (MOCK만 허용)
//
// 인증 정책: OnboardingGuardFilter /api/onboarding/... 전부 통과 허용 — 별도 처리 불요.
// 평문 키 응답 절대 금지 (메모리 feedback_secret_handling.md + ADR 0046 §2-5).

/**
 * KIS 자격증명 변경 컨트롤러 (ADR 0074 §2-2 + spec §2-2 정합).
 *
 * SettingsModal KIS 키 관리 Section 전용.
 * SetupWizard 첫 등록은 POST /api/onboarding/kis/validate (OnboardingController 담당).
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@Tag(
    name = "KIS 자격증명 관리",
    description =
        "SettingsModal — KIS 키 변경·조회·삭제 (ADR 0074 §2-2). " +
            "첫 등록은 OnboardingController(POST /api/onboarding/kis/validate) 담당. " +
            "평문 키 응답 절대 없음 — 마스킹만.",
)
@RestController
@RequestMapping("/api/onboarding/kis")
class KisCredentialChangeController(
    private val kisCredentialChangeService: KisCredentialChangeService,
) {
    @Operation(
        summary = "KIS 자격증명 마스킹 조회",
        description =
            "등록된 KIS REAL/MOCK 자격증명을 마스킹하여 반환합니다. " +
                "appKey: 앞 4자 + *** + 뒤 4자 (예: PS01***wxyz). " +
                "secretKey: 전부 ********. " +
                "row 미등록 시 hasKey=false (합성 placeholder 없음).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "마스킹 응답 성공"),
        ApiResponse(
            responseCode = "412",
            description = "온보딩 미완료 — userId 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/credentials")
    fun getCredentials(): MaskedKisCredentialResponseDto {
        val userId = kisCredentialChangeService.resolveUserId()
        return kisCredentialChangeService.getKisCredentialsMasked(userId)
    }

    @Operation(
        summary = "KIS 자격증명 변경",
        description =
            "등록된 KIS 키를 새 키로 교체합니다. " +
                "KIS OAuth tokenP 검증 통과 시에만 DB UPDATE (Atomicity LOCK). " +
                "성공 시: Redis access_token + approval_key DEL + 5초 잠금 SET. " +
                "Kafka kafka.api.evt.system.kis-key.changed.v1 publish (REAL만). " +
                "계좌번호(accountNumber) 변경 불가 — read-only. 첫 등록은 POST /api/onboarding/kis/validate 사용.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공 — 마스킹 응답 반환"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 오류 (appKey/secretKey 길이 미달 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "KIS 키 검증 실패 — 콘솔에서 재발급",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "등록된 키 없음 — SetupWizard에서 먼저 등록",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "KIS 서비스 일시 장애",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PutMapping("/{accountType}")
    fun updateCredential(
        @Parameter(
            description = "계좌 유형 — REAL(실전) 또는 MOCK(모의)",
            example = "REAL",
            required = true,
        )
        @PathVariable accountType: String,
        @Parameter(description = "새 KIS 자격증명 (appKey + secretKey)")
        @Valid
        @RequestBody request: KisCredentialUpdateRequest,
    ): MaskedKisCredentialItem {
        val userId = kisCredentialChangeService.resolveUserId()
        val normalizedType = normalizeAccountType(accountType)
        return kisCredentialChangeService.updateKisCredential(userId, normalizedType, request)
    }

    @Operation(
        summary = "KIS 자격증명 삭제",
        description =
            "등록된 KIS 키를 삭제합니다. MOCK 계좌만 허용 — REAL 삭제 시 403 반환. " +
                "(ADR 0074 §2-2-1 §h: REAL은 변경만, DELETE는 MOCK 전용)",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "삭제 성공"),
        ApiResponse(
            responseCode = "403",
            description = "REAL 삭제 금지 — 변경(PUT)만 허용",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "등록된 MOCK 키 없음",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @DeleteMapping("/{accountType}")
    fun deleteCredential(
        @Parameter(
            description = "계좌 유형 — MOCK만 허용 (REAL 요청 시 403)",
            example = "MOCK",
            required = true,
        )
        @PathVariable accountType: String,
    ): KisCredentialDeleteResponse {
        val userId = kisCredentialChangeService.resolveUserId()
        val normalizedType = normalizeAccountType(accountType)
        return kisCredentialChangeService.deleteKisCredential(userId, normalizedType)
    }

    private fun normalizeAccountType(raw: String): String {
        val normalized = raw.trim().uppercase()
        if (normalized == "REAL" || normalized == "MOCK") {
            return normalized
        }
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "accountType은 REAL 또는 MOCK이어야 합니다 (입력: $raw)",
        )
    }
}
