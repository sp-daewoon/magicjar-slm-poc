package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal

class DailyPnlCalculatorTest {
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val now = Instant.parse("2026-05-26T01:00:00Z")

    @Test
    fun `REAL mode는 RiskPositionPort 부재 시 fallback 0 대신 예외를 던진다`() {
        val calculator =
            DailyPnlCalculator(
                redisTemplate = redisTemplate,
                positionPortProvider = nullableProvider(),
                magicjarMode = "REAL",
            )

        assertThrows(IllegalStateException::class.java) {
            calculator.getDailyPnl("user-A", now)
        }
    }

    @Test
    fun `REAL mode는 DB PnL 조회 실패 시 예외를 던져 risk fail-closed로 전달한다`() {
        val port = mockk<RiskPositionPort>()
        every { port.sumDailyRealizedPnl(any(), any()) } throws RuntimeException("DB down")
        val calculator =
            DailyPnlCalculator(
                redisTemplate = redisTemplate,
                positionPortProvider = providerOf(port),
                magicjarMode = "REAL",
            )

        assertThrows(IllegalStateException::class.java) {
            calculator.getDailyPnl("user-A", now)
        }
    }

    @Test
    fun `MOCK mode는 RiskPositionPort 부재 시 기존처럼 0으로 fallback한다`() {
        val calculator =
            DailyPnlCalculator(
                redisTemplate = redisTemplate,
                positionPortProvider = nullableProvider(),
                magicjarMode = "MOCK",
            )

        assertThat(calculator.getDailyPnl("user-A", now)).isEqualByComparingTo(BigDecimal.ZERO)
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }

    private inline fun <reified T : Any> nullableProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns null
        }
}
