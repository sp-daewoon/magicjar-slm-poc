package kr.spdaewoon.magicjar.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshot
import kr.spdaewoon.magicjar.domain.account.port.AccountAvailabilitySnapshotPort
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

/**
 * 계좌 availability snapshot Redis 저장소.
 *
 * key: `account:availability:user:{userId}:mode:{mode}`.
 * value: [AccountAvailabilitySnapshot] JSON.
 *
 * 주문 hot path 는 [read]만 수행한다. KIS 조회/refresh 는 별도 worker 가 수행하고 [putIfNewer]로 LWW 저장한다.
 */
@Component
class RedisAccountAvailabilitySnapshotAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.kis.account-availability.snapshot-ttl-seconds:900}")
    private val ttlSeconds: Long = 900,
) : AccountAvailabilitySnapshotPort {
    private val log = LoggerFactory.getLogger(RedisAccountAvailabilitySnapshotAdapter::class.java)

    companion object {
        private val PUT_IF_NEWER_SCRIPT =
            DefaultRedisScript<Long>().apply {
                setScriptText(
                    """
                    local current = redis.call('GET', KEYS[1])
                    local incomingVersion = tonumber(ARGV[2])
                    if current then
                        local ok, decoded = pcall(cjson.decode, current)
                        if ok and decoded and decoded['versionMillis'] ~= nil then
                            local currentVersion = tonumber(decoded['versionMillis'])
                            if currentVersion and incomingVersion and currentVersion > incomingVersion then
                                return 0
                            end
                        end
                    end
                    redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[3]))
                    return 1
                    """.trimIndent(),
                )
                setResultType(Long::class.java)
            }
    }

    override fun read(
        userId: String,
        mode: TradingMode,
    ): AccountAvailabilitySnapshot? {
        val key = key(mode, userId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return try {
            objectMapper.readValue<AccountAvailabilitySnapshot>(json)
        } catch (e: Exception) {
            log.warn(
                "account availability snapshot 역직렬화 실패 — cache miss 처리: key={} err={}",
                maskKey(key),
                e.message,
            )
            null
        }
    }

    override fun putIfNewer(snapshot: AccountAvailabilitySnapshot): Boolean {
        val key = key(snapshot.mode, snapshot.userId)
        val stored =
            redisTemplate.execute(
                PUT_IF_NEWER_SCRIPT,
                listOf(key),
                objectMapper.writeValueAsString(snapshot),
                snapshot.versionMillis.toString(),
                ttlSeconds.coerceAtLeast(1).toString(),
            ) == 1L
        if (!stored) {
            log.debug(
                "account availability snapshot LWW skip: key={} incomingVersion={}",
                maskKey(key),
                snapshot.versionMillis,
            )
        }
        return stored
    }

    internal fun key(
        mode: TradingMode,
        userId: String,
    ): String = RedisKeyRegistry.Account.availability(mode.name, userId)

    private fun maskKey(key: String): String =
        key
            .split(":")
            .mapIndexed { index, part -> if (index == 3 && part.length >= 4) part.take(4) + "****" else part }
            .joinToString(":")
}
