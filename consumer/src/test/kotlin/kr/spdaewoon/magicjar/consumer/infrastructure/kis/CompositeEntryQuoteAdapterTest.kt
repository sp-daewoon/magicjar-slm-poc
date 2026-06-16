package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [CompositeEntryQuoteAdapter] 단위 테스트 — ADR 0114 §10-3 R2-C1 합성 우선순위.
 *
 *  (a) Redis cache hit → REST 호출 0 (1차 source 우선)
 *  (b) cache miss → REST fallback hit
 *  (c) cache miss + REST 실패 → null (호가 미가용 = 진입 스킵, 시장가 fallback 0)
 *
 * AI 호출 0.
 */
class CompositeEntryQuoteAdapterTest {
    private val cache = mockk<RedisOrderbookTopCache>()
    private val rest = mockk<KisRestAskingPriceAdapter>()
    private val adapter = CompositeEntryQuoteAdapter(cache, rest)

    private fun quote(ask: String) =
        EntryQuote(
            ask1 = BigDecimal(ask),
            bid1 = BigDecimal(ask).subtract(BigDecimal.ONE),
            askQty1 = 100L,
            asOf = Clock.System.now(),
        )

    @Test
    fun `cache hit이면 REST를 호출하지 않고 캐시 호가를 반환한다`() {
        every { cache.read("005930") } returns quote("71000")

        val result = adapter.latestQuote("005930")

        assertThat(result?.ask1).isEqualByComparingTo("71000")
        verify(exactly = 0) { rest.fetchTopQuote(any()) }
    }

    @Test
    fun `cache miss면 REST fallback으로 넘어간다`() {
        every { cache.read("005930") } returns null
        every { rest.fetchTopQuote("005930") } returns quote("70500")

        val result = adapter.latestQuote("005930")

        assertThat(result?.ask1).isEqualByComparingTo("70500")
        verify(exactly = 1) { rest.fetchTopQuote("005930") }
    }

    @Test
    fun `cache miss + REST 실패면 null을 반환한다 (시장가 fallback 0)`() {
        every { cache.read("005930") } returns null
        every { rest.fetchTopQuote("005930") } returns null

        val result = adapter.latestQuote("005930")

        assertThat(result).isNull()
    }
}
