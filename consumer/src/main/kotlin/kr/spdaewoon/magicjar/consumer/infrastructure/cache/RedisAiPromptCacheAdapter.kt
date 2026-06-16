package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.ai.AiCommentResponse
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * AI prompt 캐시 (ADR 0026 §2-5 amend — 5분 TTL).
 *
 * 키 패턴: `ai:prompt:cache:{templateHash}` — SHA-256(prompt) 16 hex prefix.
 *
 * 책임:
 * - 동일 prompt 재호출 시 LLM 호출 0건 (비용 절감)
 * - hit 시 캐시된 응답을 새 requestId 로 wrap (STOMP fanout 정합)
 *
 * 안전 가드:
 * - cache 미스 / Redis 에러 → null 반환 (호출자 LLM 호출 fallback) — 본체 영향 0
 * - cache 값 deserialize 실패 → null 반환 + WARN log + 키 삭제
 *
 * 본 cache 는 user-agnostic — 같은 prompt 면 모든 사용자 공유 (개인정보 prompt 합성 시 prompt 자체에 userId 포함됨 → 자연 분리).
 */
@Component
class RedisAiPromptCacheAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(RedisAiPromptCacheAdapter::class.java)

    companion object {
        private val TTL = Duration.ofMinutes(5)
    }

    /**
     * Cache 조회.
     *
     * @param prompt 변수 치환 완료된 최종 prompt
     * @param newRequestId 새 요청 requestId — cache hit 시 응답에 wrap (STOMP fanout 라우팅 키 정합)
     * @return cache hit 시 cached=TRUE / requestId 가 newRequestId 로 교체된 응답 / miss / 에러 시 null
     */
    fun get(
        prompt: String,
        newRequestId: UUID,
    ): AiCommentResponse? {
        val key = cacheKey(prompt)
        return try {
            val raw = redisTemplate.opsForValue().get(key) ?: return null
            val cached = objectMapper.readValue(raw, AiCommentResponse::class.java)
            cached.copy(requestId = newRequestId, cached = true, latencyMs = 0L)
        } catch (e: Exception) {
            log.warn("[AiPromptCache] get 실패 — key={} err={}", key, e.message)
            runCatching { redisTemplate.delete(key) }
            null
        }
    }

    /**
     * Cache 저장 (TTL 5분).
     *
     * @param prompt 변수 치환 완료된 prompt (해싱 키)
     * @param response 저장할 응답 (cached=false 상태로 보관 — 조회 시 cached=true 로 wrap)
     */
    fun put(
        prompt: String,
        response: AiCommentResponse,
    ) {
        val key = cacheKey(prompt)
        try {
            val payload = objectMapper.writeValueAsString(response.copy(cached = false))
            redisTemplate.opsForValue().set(key, payload, TTL)
        } catch (e: Exception) {
            log.warn("[AiPromptCache] put 실패 — key={} err={}", key, e.message)
        }
    }

    private fun cacheKey(prompt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        return RedisKeyRegistry.Ai.promptCache(hex)
    }
}
