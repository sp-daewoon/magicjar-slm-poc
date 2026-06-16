package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations
import java.time.LocalDate
import java.time.ZoneId

/**
 * TradeFrequencyRedisGuard 단위 테스트 — canary D2 hotfix #3 (일 카운트 = 당일 KST 경계).
 *
 * 과거 day 윈도는 롤링 24h(`from = now - 86400`)라 어제 거래가 오늘 한도를 잠식했다. 본 hotfix 는
 * `from = max(now - 86400, 당일 0시 KST)` 로 절단해 자정이 지나면 어제 score 가 윈도 밖으로 빠진다.
 *
 * AI 호출 0 — T-RISK 트랙 정책.
 */
class TradeFrequencyRedisGuardTest {
    private val zops = mockk<ZSetOperations<String, String>>(relaxed = true)
    private val redis =
        mockk<StringRedisTemplate>(relaxed = true).also {
            every { it.opsForZSet() } returns zops
        }
    private val guard = TradeFrequencyRedisGuard(redis)

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `kstMidnightEpoch — now 의 당일 0시(KST) epoch 를 반환`() {
        // 2026-06-12 05:00 KST = 2026-06-11T20:00:00Z. 당일 0시 = 2026-06-12T00:00 KST = 2026-06-11T15:00:00Z.
        val now = Instant.parse("2026-06-11T20:00:00Z")
        val expected =
            LocalDate.of(2026, 6, 12).atStartOfDay(kst).toEpochSecond()

        assertThat(TradeFrequencyRedisGuard.kstMidnightEpoch(now)).isEqualTo(expected)
    }

    @Test
    fun `countRecentDay — 아침이라 당일경계가 롤링24h보다 최근이면 from 하한은 당일 0시(KST)`() {
        // 2026-06-12 05:00 KST → now-86400(=어제 05:00)은 당일 0시(=오늘 00:00)보다 과거.
        // 따라서 from = 당일 0시 KST 로 끌어올려 어제 거래 제외.
        val now = Instant.parse("2026-06-11T20:00:00Z") // 2026-06-12 05:00 KST
        val midnight =
            LocalDate
                .of(2026, 6, 12)
                .atStartOfDay(kst)
                .toEpochSecond()
                .toDouble()
        val fromSlot = slot<Double>()
        every { zops.count(any(), capture(fromSlot), any()) } returns 2L

        val count = guard.countRecentDay(strategyId = 8L, userId = "user-A", now = now)

        assertThat(count).isEqualTo(2)
        // from 하한이 롤링 24h(now-86400) 가 아니라 당일 0시 KST.
        assertThat(fromSlot.captured).isEqualTo(midnight)
    }

    @Test
    fun `record day — 당일 0시(KST) 미만 score 를 trim (어제 거래 카운트 이탈)`() {
        val now = Instant.parse("2026-06-11T20:00:00Z") // 2026-06-12 05:00 KST
        val midnight =
            LocalDate
                .of(2026, 6, 12)
                .atStartOfDay(kst)
                .toEpochSecond()
                .toDouble()
        // day 키 trim 하한 capture (minute 키 trim 과 구분하기 위해 day 키 패턴만 검증).
        val dayKey = "risk:trade-frequency:user:user-A:strategy:8:window:day"
        val trimSlot = slot<Double>()
        every { zops.removeRangeByScore(dayKey, 0.0, capture(trimSlot)) } returns 0L

        guard.record(strategyId = 8L, userId = "user-A", signalId = 77L, now = now)

        // day ZSET trim 하한 = max(now-86400, 당일 0시 KST) = 당일 0시(아침이므로).
        verify { zops.add(dayKey, "77", now.epochSeconds.toDouble()) }
        assertThat(trimSlot.captured).isEqualTo(midnight)
    }

    @Test
    fun `removeRecord — minute 와 day 카운트에서 signalId 를 제거`() {
        guard.removeRecord(strategyId = 8L, userId = "user-A", signalId = 77L)

        verify { zops.remove("risk:trade-frequency:user:user-A:strategy:8:window:minute", "77") }
        verify { zops.remove("risk:trade-frequency:user:user-A:strategy:8:window:day", "77") }
    }

    @Test
    fun `countRecentMinute — 롤링 60s 윈도 그대로(당일 경계 무관)`() {
        val now = Instant.parse("2026-06-11T20:00:00Z")
        val fromSlot = slot<Double>()
        every { zops.count(any(), capture(fromSlot), any()) } returns 1L

        guard.countRecentMinute(strategyId = 8L, userId = "user-A", now = now)

        // minute 은 floor 미적용 — from = now - 60.
        assertThat(fromSlot.captured).isEqualTo(now.epochSeconds.toDouble() - 60.0)
    }
}
