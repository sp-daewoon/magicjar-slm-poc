package kr.spdaewoon.magicjar.api.settings

import kr.spdaewoon.magicjar.api.controller.KisCredentialChangeController
import kr.spdaewoon.magicjar.api.controller.SystemConfigController
import kr.spdaewoon.magicjar.api.controller.SystemConfigResponse
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.service.KillSwitchService
import kr.spdaewoon.magicjar.api.service.KisCredentialChangeService
import kr.spdaewoon.magicjar.api.service.OnboardingService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * 5 endpoint Swagger 4단 + HTTP 상태코드 검증 (spec §2-4-1 정합).
 *
 * WebMvcTest — 서비스 레이어 Mock, 컨트롤러 레이어 단위 검증.
 *
 * 검증 항목:
 *   - GET  /api/onboarding/kis/credentials          → 200
 *   - PUT  /api/onboarding/kis/REAL                 → 400 (body 없음)
 *   - DELETE /api/onboarding/kis/REAL               → 403 (REAL 삭제 금지)
 *   - GET  /api/system-config/kill-switch            → 200
 *   - PUT  /api/system-config/kill-switch            → 400 (reason 미달)
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
@WebMvcTest(
    controllers = [KisCredentialChangeController::class, SystemConfigController::class],
)
@TestPropertySource(
    properties = [
        "magicjar.secret.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ],
)
class SettingsEndpointSwaggerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var kisCredentialChangeService: KisCredentialChangeService

    @MockBean
    private lateinit var killSwitchService: KillSwitchService

    @MockBean
    private lateinit var onboardingService: OnboardingService

    // OnboardingGuardFilter 의존성 — WebMvcTest 컨텍스트 로드 필요
    @MockBean
    private lateinit var userAccountJpaRepository: UserAccountJpaRepository

    @Test
    fun `GET credentials - resolveUserId 반환 시 200 응답`() {
        // given
        `when`(kisCredentialChangeService.resolveUserId()).thenReturn("74683223")
        `when`(kisCredentialChangeService.getKisCredentialsMasked("74683223")).thenReturn(
            kr.spdaewoon.magicjar.api.controller.MaskedKisCredentialResponseDto(
                userId = "74683223",
                credentials = emptyList(),
            ),
        )

        // when + then
        mockMvc
            .get("/api/onboarding/kis/credentials")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `PUT credential - body 없음 시 400 응답`() {
        // when + then
        mockMvc
            .put("/api/onboarding/kis/REAL") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `DELETE credential REAL - 403 반환 확인`() {
        // given
        `when`(kisCredentialChangeService.resolveUserId()).thenReturn("74683223")
        `when`(kisCredentialChangeService.deleteKisCredential("74683223", "REAL"))
            .thenThrow(
                ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    """{"code":"REAL_DELETE_FORBIDDEN","message":"실전 계좌 자격증명은 삭제할 수 없습니다"}""",
                ),
            )

        // when + then
        mockMvc
            .delete("/api/onboarding/kis/REAL")
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `GET kill-switch - resolveUserId 반환 시 200 응답`() {
        // given
        `when`(kisCredentialChangeService.resolveUserId()).thenReturn("74683223")
        `when`(killSwitchService.getState("74683223")).thenReturn(
            kr.spdaewoon.magicjar.api.controller.KillSwitchStateResponse(
                enabled = false,
                reason = null,
                updatedAt = null,
                updatedBy = null,
            ),
        )

        // when + then
        mockMvc
            .get("/api/system-config/kill-switch")
            .andExpect {
                status { isOk() }
                jsonPath("$.enabled") { value(false) }
            }
    }

    @Test
    fun `GET system-config - 저장된 평문 설정 반환`() {
        `when`(userAccountJpaRepository.existsAny()).thenReturn(true)
        `when`(onboardingService.getSystemConfig()).thenReturn(
            SystemConfigResponse(
                hasDartKey = true,
                hasSlackWebhook = true,
                dartApiKey = "dart-key",
                slackWebhookUrl = "https://hooks.slack.com/services/T00/B00/xxx",
                realtimeFallbackBuyEnabled = true,
                updatedAt = Instant.parse("2026-06-01T00:00:00Z"),
            ),
        )

        mockMvc
            .get("/api/system-config")
            .andExpect {
                status { isOk() }
                jsonPath("$.dartApiKey") { value("dart-key") }
                jsonPath("$.slackWebhookUrl") { value("https://hooks.slack.com/services/T00/B00/xxx") }
            }
    }

    @Test
    fun `PUT kill-switch - reason 미달 시 400 응답`() {
        // when + then (reason이 5자 미만 — "단" = 1자)
        mockMvc
            .put("/api/system-config/kill-switch") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = """{"engaged":false,"reason":"단"}"""
            }.andExpect {
                status { isBadRequest() }
            }
    }
}
