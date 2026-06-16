package kr.spdaewoon.magicjar.api.runtime

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.controller.runtime.RuntimeFlagsController
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaEntity
import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteEntity
import kr.spdaewoon.magicjar.api.persistence.killswitch.KillSwitchWriteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

/**
 * RuntimeFlagsController 단위 테스트 — ADR 0084 P2-2 (frontend SettingsModal·StatusBar 표시 prereq).
 *
 * 9 케이스:
 *  (1) MOCK 모드 + allowReal=false + userId 없음 — raw env 값 echo + userConsented=null
 *  (2) REAL 모드 + allowReal=true + userId 없음 — env 값 echo + 사용자 무관 응답
 *  (3) userId 지정 + KillSwitch ON — 사용자별 row enabled=true 반환
 *  (4) userId 지정 + KillSwitch row 없음 — true 반환 (fail-closed)
 *  (5) userId 지정 + userConsented true — realTradingConsentAt 존재 시 true
 *  (6) userId 지정 + userConsented false — realTradingConsentAt null 시 false
 *  (7) userId 없음 + KillSwitch OR — 한 row라도 enabled=true면 true
 *  (8) magicjarMode 비정상 값 — MOCK으로 정규화
 *  (9) KillSwitch 조회 실패 — true 반환 (fail-closed)
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
class RuntimeFlagsControllerTest {
    private val killSwitchRepo = mockk<KillSwitchWriteJpaRepository>(relaxed = true)
    private val userAccountRepo = mockk<UserAccountJpaRepository>(relaxed = true)

    private fun controller(
        mode: String = "MOCK",
        allowReal: Boolean = false,
    ): RuntimeFlagsController =
        RuntimeFlagsController(
            magicjarMode = mode,
            magicjarAllowReal = allowReal,
            killSwitchWriteJpaRepository = killSwitchRepo,
            userAccountJpaRepository = userAccountRepo,
        )

    private fun killSwitchRow(
        userId: String,
        enabled: Boolean,
    ): KillSwitchWriteEntity =
        KillSwitchWriteEntity(
            userId = userId,
            enabled = enabled,
            reason = if (enabled) "test" else null,
            updatedAt = Instant.parse("2026-05-19T01:00:00Z"),
            updatedBy = "TEST",
        )

    private fun userAccountRow(
        userId: String,
        consentAt: Instant?,
    ): UserAccountJpaEntity =
        UserAccountJpaEntity(
            userId = userId,
            displayName = "test",
            maskedAccount = "1234****",
            createdAt = Instant.parse("2026-05-01T00:00:00Z"),
            lastLoginAt = null,
            realTradingConsentAt = consentAt,
        )

    @Test
    fun `(1) MOCK 모드 + allowReal=false + userId 없음 — raw env echo + userConsented=null`() {
        every { killSwitchRepo.findAll() } returns emptyList()

        val response = controller(mode = "MOCK", allowReal = false).getFlags(userId = null)

        assertThat(response.magicjarMode).isEqualTo("MOCK")
        assertThat(response.magicjarAllowReal).isFalse
        assertThat(response.killSwitchActive).isFalse
        assertThat(response.userConsented).isNull()
    }

    @Test
    fun `(2) REAL 모드 + allowReal=true + userId 없음 — env 값 echo + 사용자 무관`() {
        every { killSwitchRepo.findAll() } returns emptyList()

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = null)

        assertThat(response.magicjarMode).isEqualTo("REAL")
        assertThat(response.magicjarAllowReal).isTrue
        assertThat(response.killSwitchActive).isFalse
        assertThat(response.userConsented).isNull()
        // userId 없음 → userAccountRepository.findById 호출 0건 (계좌 무관 응답)
        verify(exactly = 0) { userAccountRepo.findById(any()) }
    }

    @Test
    fun `(3) userId 지정 + KillSwitch ON — 사용자별 row enabled=true 반환`() {
        every { killSwitchRepo.findById("12345678") } returns Optional.of(killSwitchRow("12345678", enabled = true))
        every { userAccountRepo.findById("12345678") } returns
            Optional.of(userAccountRow("12345678", consentAt = null))

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = "12345678")

        assertThat(response.killSwitchActive).isTrue
        assertThat(response.userConsented).isFalse
    }

    @Test
    fun `(4) userId 지정 + KillSwitch row 없음 — true 반환 (fail closed)`() {
        every { killSwitchRepo.findById("99999999") } returns Optional.empty()
        every { userAccountRepo.findById("99999999") } returns Optional.empty()

        val response = controller(mode = "MOCK", allowReal = false).getFlags(userId = "99999999")

        assertThat(response.killSwitchActive).isTrue
        // user_account row 없음 → orElse(false) — null이 아닌 false (지정된 userId가 있으므로)
        assertThat(response.userConsented).isFalse
    }

    @Test
    fun `(5) userId 지정 + userConsented true — realTradingConsentAt 존재 시 true`() {
        every { killSwitchRepo.findById("12345678") } returns Optional.of(killSwitchRow("12345678", enabled = false))
        every { userAccountRepo.findById("12345678") } returns
            Optional.of(
                userAccountRow("12345678", consentAt = Instant.parse("2026-05-13T09:00:00Z")),
            )

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = "12345678")

        assertThat(response.userConsented).isTrue
    }

    @Test
    fun `(6) userId 지정 + userConsented false — realTradingConsentAt null 시 false`() {
        every { killSwitchRepo.findById("12345678") } returns Optional.of(killSwitchRow("12345678", enabled = false))
        every { userAccountRepo.findById("12345678") } returns
            Optional.of(userAccountRow("12345678", consentAt = null))

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = "12345678")

        assertThat(response.userConsented).isFalse
    }

    @Test
    fun `(7) userId 없음 + KillSwitch OR — 한 row라도 enabled=true면 true`() {
        every { killSwitchRepo.findAll() } returns
            listOf(
                killSwitchRow("11111111", enabled = false),
                killSwitchRow("22222222", enabled = true),
            )

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = null)

        assertThat(response.killSwitchActive).isTrue
    }

    @Test
    fun `(8) magicjarMode 비정상 값 — MOCK으로 정규화`() {
        every { killSwitchRepo.findAll() } returns emptyList()

        val response = controller(mode = "garbage-value", allowReal = false).getFlags(userId = null)

        assertThat(response.magicjarMode).isEqualTo("MOCK")
    }

    @Test
    fun `(9) KillSwitch 조회 실패 — true 반환 fail closed`() {
        every { killSwitchRepo.findById("12345678") } throws IllegalStateException("db down")
        every { userAccountRepo.findById("12345678") } returns Optional.empty()

        val response = controller(mode = "REAL", allowReal = true).getFlags(userId = "12345678")

        assertThat(response.killSwitchActive).isTrue
        assertThat(response.userConsented).isFalse
    }
}
