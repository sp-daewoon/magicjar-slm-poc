package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations

/**
 * [RedisOrderbookTopCache] 단위 테스트 — ADR 0114 §10-3 R2-C1 1차 source 파싱.
 *
 *  (a) 정상 JSON → EntryQuote 매핑 (ask1·bid1·askQty1·asOf)
 *  (b) 캐시 miss(null) → null
 *  (c) ask1 ≤ 0 (빈 호가) → null (진입가 오염 방지)
 *  (d) ts 누락 → null
 *  (e) askQty1 누락 → null 허용 (R2 thin-liquidity 입력 optional)
 *
 * AI 호출 0.
 */
class RedisOrderbookTopCacheTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val objectMapper = ObjectMapper()
    private val cache = RedisOrderbookTopCache(redisTemplate, objectMapper)

    private fun stub(
        code: String,
        json: String?,
    ) {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.get("market:orderbook:top:code:$code") } returns json
    }

    @Test
    fun `정상 JSON이면 EntryQuote로 매핑한다`() {
        stub(
            "005930",
            """{"ask1":"71000","bid1":"70900","askQty1":"250","bidQty1":"300","ts":"2026-06-07T05:30:00Z"}""",
        )

        val q = cache.read("005930")

        assertThat(q).isNotNull
        assertThat(q!!.ask1).isEqualByComparingTo("71000")
        assertThat(q.bid1).isEqualByComparingTo("70900")
        assertThat(q.askQty1).isEqualTo(250L)
        assertThat(q.asOf.toString()).isEqualTo("2026-06-07T05:30:00Z")
    }

    @Test
    fun `캐시 miss면 null을 반환한다`() {
        stub("005930", null)
        assertThat(cache.read("005930")).isNull()
    }

    @Test
    fun `ask1이 0이면 null을 반환한다 (빈 호가 오염 방지)`() {
        stub("005930", """{"ask1":"0","bid1":"70900","askQty1":"250","ts":"2026-06-07T05:30:00Z"}""")
        assertThat(cache.read("005930")).isNull()
    }

    @Test
    fun `ts가 없으면 null을 반환한다`() {
        stub("005930", """{"ask1":"71000","bid1":"70900","askQty1":"250"}""")
        assertThat(cache.read("005930")).isNull()
    }

    @Test
    fun `askQty1이 없어도 매핑된다 (잔량은 optional)`() {
        stub("005930", """{"ask1":"71000","bid1":"70900","ts":"2026-06-07T05:30:00Z"}""")

        val q = cache.read("005930")

        assertThat(q).isNotNull
        assertThat(q!!.askQty1).isNull()
    }
}
