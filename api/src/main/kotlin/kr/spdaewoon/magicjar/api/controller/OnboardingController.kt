package kr.spdaewoon.magicjar.api.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.spdaewoon.magicjar.api.service.OnboardingService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// ADR 0046, spec 4-1 LOCK 정합
// 5 endpoint:
//   GET  /api/onboarding/status         -- 셋업 진행 상태 조회
//   POST /api/onboarding/kis/validate   -- KIS 실전 키 검증, 등록
//   POST /api/onboarding/kis/mock       -- KIS 모의 키 검증, 등록
//   POST /api/onboarding/system-config  -- DART, Slack 설정
//   POST /api/onboarding/complete       -- 셋업 완료 처리
//
// 인증 정책 (spec 4-7): OnboardingGuardFilter가 /api/onboarding/... 전부 통과 허용.
@Tag(name = "온보딩", description = "셋업 마법사 — KIS 키 등록 7-step 흐름 (ADR 0046)")
@RestController
@RequestMapping("/api/onboarding")
class OnboardingController(
    private val onboardingService: OnboardingService,
) {
    @Operation(
        summary = "셋업 진행 상태 조회",
        description =
            "user_account·user_kis_credential·system_config 4 항목의 등록 여부를 반환합니다. " +
                "첫 부팅 시 frontend가 본 endpoint로 마법사 분기 결정. 인증 불요.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(
            responseCode = "500",
            description = "DB 다운",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @GetMapping("/status")
    fun status(): OnboardingStatus = onboardingService.getStatus()

    @Operation(
        summary = "KIS 실전 키 검증 + 등록",
        description =
            "KIS OAuth tokenP 호출로 실전 키 검증 후 AES-256-GCM 암호화 및 DB 영속. " +
                "첫 등록 시 인증 불요. 이미 등록 시 409 반환.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검증·등록 성공"),
        ApiResponse(
            responseCode = "400",
            description = "입력값 오류 (accountNumber 8자리 등)",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "KIS 키 검증 실패",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "이미 등록된 계좌",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "KIS 서비스 일시 장애",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/kis/validate")
    fun validateKisReal(
        @Parameter(description = "KIS 실전 키 등록 요청")
        @Valid
        @RequestBody request: KisValidateRequest,
    ): KisValidateResponse = onboardingService.validateAndPersistKisReal(request)

    @Operation(
        summary = "KIS 모의 계좌 키 검증 + 등록",
        description =
            "KIS 모의 서버(openapivts:29443)로 모의 키 검증 후 AES-256-GCM 암호화 및 DB 영속. " +
                "실전 계좌 등록이 선행 필요. 건너뛰기 가능 (Step 4 선택).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "검증·등록 성공"),
        ApiResponse(responseCode = "400", description = "입력값 오류"),
        ApiResponse(responseCode = "401", description = "KIS 모의 키 검증 실패"),
        ApiResponse(responseCode = "404", description = "실전 계좌 미등록"),
        ApiResponse(responseCode = "409", description = "이미 등록된 모의 계좌"),
        ApiResponse(responseCode = "503", description = "KIS 서비스 일시 장애"),
    )
    @PostMapping("/kis/mock")
    fun validateKisMock(
        @Parameter(description = "KIS 모의 키 등록 요청")
        @Valid
        @RequestBody request: KisMockRequest,
    ): KisMockResponse = onboardingService.validateAndPersistKisMock(request)

    @Operation(
        summary = "시스템 전역 설정 (DART, Slack)",
        description =
            "DART API 키, Slack webhook URL을 등록, 갱신, 삭제합니다. " +
                "null = 변경 안 함, 빈 문자열 = 삭제. Slack webhook 등록 시 test message 1회 발송 검증. " +
                "건너뛰기 가능 (Step 5 선택).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "설정 갱신 성공"),
        ApiResponse(responseCode = "400", description = "입력값 오류"),
        ApiResponse(responseCode = "503", description = "Slack webhook 검증 실패"),
    )
    @PostMapping("/system-config")
    fun updateSystemConfig(
        @Parameter(description = "시스템 전역 설정 요청")
        @Valid
        @RequestBody request: SystemConfigRequest,
    ): SystemConfigResponse = onboardingService.updateSystemConfig(request)

    @Operation(
        summary = "셋업 완료 처리",
        description =
            "셋업 마법사를 완료합니다. KIS 실전 계좌 등록이 선행 필요 (없으면 412). " +
                "완료 후 UserOnboardedEvent 발행 — consumer가 polling 시작 (PR-7+).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "셋업 완료 성공 + Dashboard 리다이렉트 경로 반환"),
        ApiResponse(responseCode = "401", description = "인증 오류"),
        ApiResponse(responseCode = "412", description = "KIS 실전 계좌 미등록"),
    )
    @PostMapping("/complete")
    fun complete(
        @Parameter(description = "온보딩 완료 요청")
        @Valid
        @RequestBody request: OnboardingCompleteRequest,
    ): OnboardingCompleteResponse = onboardingService.complete(request.userId)
}
