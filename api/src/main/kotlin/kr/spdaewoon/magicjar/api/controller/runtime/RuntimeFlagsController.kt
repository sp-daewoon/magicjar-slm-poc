package kr.spdaewoon.magicjar.api.controller.runtime

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 런타임 플래그 노출 REST 컨트롤러 (ADR 0084 P2-2 — frontend SettingsModal·StatusBar 표시 prereq).
 *
 * frontend가 사용자 화면 상단에 "현재 모드: MOCK/REAL · KillSwitch ON/OFF · 실거래 동의 ON/OFF" 표시할 수 있도록
 * 단일 read-only endpoint로 모든 런타임 게이트 상태를 합쳐서 반환합니다.
 *
 * ### Source
 * - magicjarMode      : application.yml `magicjar.mode` (env MAGICJAR_MODE)
 * - magicjarAllowReal : application.yml `magicjar.allow-real` (env MAGICJAR_ALLOW_REAL)
 * - killSwitchActive  : kill_switch_state DB row (userId 있으면 사용자별 fail-closed, 없으면 모든 사용자 OR)
 * - userConsented     : user_account.real_trading_consent_at (userId 있을 때만 조회)
 *
 * ### 3중 게이트 (ADR 0068 §14-3)
 * 실주문 실행 가능 조건 = magicjarMode=REAL AND magicjarAllowReal=true AND userConsented=true AND killSwitchActive=false.
 * 본 endpoint는 raw 게이트 값만 반환 — 합성 boolean (canTrade)은 frontend에서 조합 LOCK.
 *
 * Swagger 4단 어노테이션 (사용자 메모리 feedback_swagger_pattern 정합).
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Tag(name = "Runtime Flags", description = "런타임 모드·게이트 플래그 노출 — frontend 화면 표시 (ADR 0084 P2-2)")
@RestController
@RequestMapping("/api/runtime")
class RuntimeFlagsController(
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.allow-real:false}")
    private val magicjarAllowReal: Boolean,
    private val killSwitchWriteJpaRepository: KillSwitchWriteJpaRepository,
    private val userAccountJpaRepository: UserAccountJpaRepository,
) {
    private val log = LoggerFactory.getLogger(RuntimeFlagsController::class.java)

    @Operation(
        summary = "런타임 플래그 조회",
        description =
            "현재 magicJar 런타임의 모드·게이트 플래그를 한 번에 반환합니다. " +
                "frontend는 본 응답으로 SettingsModal·StatusBar에 'MOCK/REAL · KillSwitch · 실거래 동의' 상태를 표시합니다. " +
                "userId 미지정 시 userConsented는 null (계좌 무관 응답) — 일반 페이지 진입용. " +
                "userId 지정 시 사용자별 KillSwitch row + 실거래 동의 시각을 함께 반영합니다. " +
                "ADR 0084 P2-2 정합 (frontend PR #549 hook 의존, ADR 0068 §14-3 3중 게이트).",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "런타임 플래그 응답",
            content = [Content(schema = Schema(implementation = RuntimeFlagsResponse::class))],
        ),
    )
    @GetMapping("/flags")
    fun getFlags(
        @Parameter(
            description = "사용자 ID (선택) — 지정 시 사용자별 KillSwitch + 실거래 동의 포함",
            example = "12345678",
            required = false,
        )
        @RequestParam(required = false) userId: String?,
    ): RuntimeFlagsResponse {
        val normalizedMode = magicjarMode.trim().uppercase().takeIf { it in MODE_ALLOWED } ?: "MOCK"
        val killSwitchActive = resolveKillSwitchActive(userId)
        val userConsented = resolveUserConsented(userId)
        log.debug(
            "GET /api/runtime/flags — userId={} mode={} allowReal={} killSwitch={} consent={}",
            userId,
            normalizedMode,
            magicjarAllowReal,
            killSwitchActive,
            userConsented,
        )
        return RuntimeFlagsResponse(
            magicjarMode = normalizedMode,
            magicjarAllowReal = magicjarAllowReal,
            killSwitchActive = killSwitchActive,
            userConsented = userConsented,
        )
    }

    // --- private helper ---

    /**
     * KillSwitch active 여부 해석.
     *
     * - userId 지정: 해당 row enabled. row 없음·조회 실패는 주문 경로와 동일하게 true(fail-closed)
     * - userId null: 모든 사용자 OR — 한 명이라도 enabled=true면 true (조회 실패는 true)
     */
    private fun resolveKillSwitchActive(userId: String?): Boolean =
        try {
            if (userId.isNullOrBlank()) {
                killSwitchWriteJpaRepository.findAll().any { it.enabled }
            } else {
                killSwitchWriteJpaRepository
                    .findById(userId)
                    .map { it.enabled }
                    .orElseGet {
                        log.error("RuntimeFlags KillSwitch row 없음 — fail-closed ON: userId={}****", userId.take(4))
                        true
                    }
            }
        } catch (e: Exception) {
            log.error("RuntimeFlags KillSwitch 조회 실패 — fail-closed ON: userId={} err={}", userId?.take(4), e.message)
            true
        }

    /**
     * 사용자 실거래 동의 여부 (userId 지정 시만).
     *
     * userId null → null 반환 (계좌 무관 응답 — 단순 모드 노출용).
     */
    private fun resolveUserConsented(userId: String?): Boolean? =
        if (userId.isNullOrBlank()) {
            null
        } else {
            userAccountJpaRepository.findById(userId).map { it.realTradingConsentAt != null }.orElse(false)
        }

    companion object {
        private val MODE_ALLOWED = setOf("MOCK", "REAL")
    }
}

/**
 * 런타임 플래그 응답 DTO (ADR 0084 P2-2).
 *
 * 합성 canTrade boolean 0건 — frontend가 4 필드를 조합. 백엔드는 raw 게이트 값만 노출.
 */
@Schema(description = "런타임 플래그 응답 (모드·게이트 raw 값)")
data class RuntimeFlagsResponse(
    @field:Schema(
        description = "현재 magicJar 운영 모드 (MOCK · REAL — env MAGICJAR_MODE)",
        example = "MOCK",
        allowableValues = ["MOCK", "REAL"],
    )
    val magicjarMode: String,
    @field:Schema(
        description = "실주문 허용 게이트 (env MAGICJAR_ALLOW_REAL — true여도 모드 REAL 아니면 무력)",
        example = "false",
    )
    val magicjarAllowReal: Boolean,
    @field:Schema(
        description = "KillSwitch 활성 상태 (true=주문 차단) — userId 지정 시 사용자별, 미지정 시 OR 합산",
        example = "false",
    )
    val killSwitchActive: Boolean,
    @field:Schema(
        description = "사용자 실거래 동의 (userId 지정 시만, 미지정 시 null)",
        example = "true",
        nullable = true,
    )
    val userConsented: Boolean?,
)
