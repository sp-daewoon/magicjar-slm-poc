package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.adapter.redis.InstrumentSearchIndexAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.redis.connection.RedisZSetCommands
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations

class InstrumentSearchIndexAdapterTest {
    private val redisTemplate: StringRedisTemplate = mock()
    private val zSetOps: ZSetOperations<String, String> = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val adapter = InstrumentSearchIndexAdapter(redisTemplate)

    @BeforeEach
    fun setUp() {
        whenever(redisTemplate.opsForZSet()).thenReturn(zSetOps)
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
    }

    @Test
    fun `search — 앞자리 0 제외 코드 조각으로 검색`() {
        whenever(zSetOps.rangeByLex(eq("instrument:search:prefix:38"), any<RedisZSetCommands.Range>()))
            .thenReturn(emptySet())
        whenever(zSetOps.rangeByLex(eq("instrument:search:prefix:code:38"), any<RedisZSetCommands.Range>()))
            .thenReturn(setOf("38110\t038110"))
        whenever(valueOps.get("instrument:summary:code:038110"))
            .thenReturn(
                """{"code":"038110","name":"에코플라스틱","market":"KOSDAQ","sector":"운송장비·부품","active":true}""",
            )

        val results = adapter.search("38110", limit = 10, market = null)

        assertThat(results).hasSize(1)
        assertThat(results[0].code).isEqualTo("038110")
        assertThat(results[0].name).isEqualTo("에코플라스틱")
    }
}
