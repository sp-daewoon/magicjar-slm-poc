package kr.spdaewoon.magicjar.api.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.api.service.risk.RiskConfigService
import kr.spdaewoon.magicjar.api.service.risk.RiskConfigUpdateRequest
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.persistence.shared.risk.RiskConfigJpaAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RiskConfigServiceTest {
    private val riskConfigJpaAdapter = mockk<RiskConfigJpaAdapter>()
    private val service = RiskConfigService(riskConfigJpaAdapter)

    @Test
    fun `DLL 비활성 요청은 기존 DLL 값을 모두 지워서 저장한다`() {
        every { riskConfigJpaAdapter.findByUserId(USER_ID) } returns config(dllPct = BigDecimal("3.00"))
        every { riskConfigJpaAdapter.save(any()) } answers { firstArg() }

        val saved =
            service.save(
                USER_ID,
                RiskConfigUpdateRequest(isDailyLossLimitActive = false),
            )

        assertThat(saved.dailyLossLimitAmount).isNull()
        assertThat(saved.dailyLossLimitPercent).isNull()
        assertThat(saved.isDailyLossLimitActive).isFalse()
        verify(exactly = 1) {
            riskConfigJpaAdapter.save(
                match {
                    it.dailyLossLimitAmount == null && it.dailyLossLimitPercent == null
                },
            )
        }
    }

    @Test
    fun `금액 기준 DLL 요청은 금액만 저장하고 비율은 지운다`() {
        every { riskConfigJpaAdapter.findByUserId(USER_ID) } returns config(dllPct = BigDecimal("3.00"))
        every { riskConfigJpaAdapter.save(any()) } answers { firstArg() }

        val saved =
            service.save(
                USER_ID,
                RiskConfigUpdateRequest(
                    isDailyLossLimitActive = true,
                    dllAbs = BigDecimal("50000"),
                    dllPct = null,
                ),
            )

        assertThat(saved.dailyLossLimitAmount).isEqualByComparingTo("50000")
        assertThat(saved.dailyLossLimitPercent).isNull()
        assertThat(saved.isDailyLossLimitActive).isTrue()
    }

    @Test
    fun `DLL 금액과 비율을 동시에 보내면 저장하지 않고 거부한다`() {
        every { riskConfigJpaAdapter.findByUserId(USER_ID) } returns config(dllPct = BigDecimal("3.00"))

        assertThatThrownBy {
            service.save(
                USER_ID,
                RiskConfigUpdateRequest(
                    isDailyLossLimitActive = true,
                    dllAbs = BigDecimal("50000"),
                    dllPct = BigDecimal("3.00"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("both non-null is invalid")

        verify(exactly = 0) { riskConfigJpaAdapter.save(any()) }
    }

    private fun config(
        dllAbs: BigDecimal? = null,
        dllPct: BigDecimal? = null,
    ): RiskConfig =
        RiskConfig(
            userId = USER_ID,
            maxPositionAmount = BigDecimal("1000000"),
            maxPositionPercent = BigDecimal("10.00"),
            dailyLossLimitAmount = dllAbs,
            dailyLossLimitPercent = dllPct,
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        const val USER_ID = "74683223"
        val NOW: Instant = Instant.parse("2026-06-02T00:00:00Z")
    }
}
