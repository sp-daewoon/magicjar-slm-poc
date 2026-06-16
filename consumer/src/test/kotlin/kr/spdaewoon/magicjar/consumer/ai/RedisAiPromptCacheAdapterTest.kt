package kr.spdaewoon.magicjar.consumer.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisAiPromptCacheAdapter
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

/**
 * RedisAiPromptCacheAdapter — TTL 5분 · hit/miss · 새 requestId wrap 검증.
 */
class RedisAiPromptCacheAdapterTest {
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val redisTemplate =
        mockk<StringRedisTemplate>(relaxed = true).apply {
            every { opsForValue() } returns valueOps
        }
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val cache = RedisAiPromptCacheAdapter(redisTemplate, objectMapper)

    @Test
    fun `miss - 키 없으면 null 반환`() {
        every { valueOps.get(any()) } returns null
        cache.get("prompt", UUID.randomUUID()).shouldBeNull()
    }

    @Test
    fun `hit - cached true 와 새 requestId 로 wrap`() {
        val original =
            AiCommentResponse(
                requestId = UUID.randomUUID(),
                provider = "anthropic",
                model = "claude-3-5-haiku",
                content = "Hello",
                tokensIn = 100,
                tokensOut = 50,
                latencyMs = 0L,
                cached = false,
            )
        every { valueOps.get(any()) } returns objectMapper.writeValueAsString(original)

        val newRequestId = UUID.randomUUID()
        val cached = cache.get("prompt", newRequestId)
        cached.shouldNotBeNull()
        cached.requestId shouldBe newRequestId
        cached.cached shouldBe true
        cached.content shouldBe "Hello"
        cached.tokensIn shouldBe 100
        cached.latencyMs shouldBe 0L
    }

    @Test
    fun `put - TTL 5분으로 set`() {
        val response =
            AiCommentResponse(
                requestId = UUID.randomUUID(),
                provider = "anthropic",
                model = "haiku",
                content = "X",
                tokensIn = 1,
                tokensOut = 1,
                latencyMs = 100,
            )
        cache.put("prompt", response)
        verify(exactly = 1) { valueOps.set(any(), any(), Duration.ofMinutes(5)) }
    }

    @Test
    fun `deserialize 실패 - null 반환 + 키 삭제 (corruption recovery)`() {
        every { valueOps.get(any()) } returns "{not json"
        cache.get("prompt", UUID.randomUUID()).shouldBeNull()
        verify(exactly = 1) { redisTemplate.delete(any<String>()) }
    }
}
