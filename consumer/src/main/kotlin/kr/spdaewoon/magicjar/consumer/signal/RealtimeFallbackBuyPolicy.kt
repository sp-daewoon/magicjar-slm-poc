package kr.spdaewoon.magicjar.consumer.signal

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.persistence.shared.system.SystemConfigJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * fallback source candle 신규 BUY 허용 정책.
 *
 * OFF여도 SELL 평가는 막지 않는다. 손절/익절/보유 청산은 자본 회수 경로이기 때문이다.
 */
@Component
class RealtimeFallbackBuyPolicy(
    private val systemConfigRepositoryProvider: ObjectProvider<SystemConfigJpaRepository>,
    @Value("\${magicjar.signal.fallback-buy.default-enabled:true}")
    private val defaultEnabled: Boolean = true,
    @Value("\${magicjar.signal.fallback-buy.cache-ttl-ms:5000}")
    private val cacheTtlMs: Long = 5000L,
) {
    private val log = LoggerFactory.getLogger(RealtimeFallbackBuyPolicy::class.java)
    private val cache = AtomicReference(CacheEntry(defaultEnabled, 0L))

    fun allowsBuy(candle: Candle): Boolean {
        if (!isFallbackSource(candle.source)) return true
        return currentEnabled()
    }

    fun isFallbackSource(source: String?): Boolean {
        val normalized = source?.trim()?.uppercase().orEmpty()
        if (normalized.isBlank()) return false
        return normalized == "KIS-POLLING" ||
            normalized == "KIS-NOTICK-FALLBACK" ||
            normalized.contains("FALLBACK") ||
            normalized.contains("POLLING")
    }

    private fun currentEnabled(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cache.get()
        if (now - cached.loadedAtMs < cacheTtlMs.coerceAtLeast(0L)) {
            return cached.enabled
        }

        val repository = systemConfigRepositoryProvider.ifAvailable ?: return defaultEnabled
        val enabled =
            runCatching {
                repository.findSingleton()?.realtimeFallbackBuyEnabled ?: defaultEnabled
            }.onFailure { e ->
                log.warn("fallback BUY 정책 조회 실패 — default={} 적용: err={}", defaultEnabled, e.message)
            }.getOrDefault(defaultEnabled)
        cache.set(CacheEntry(enabled, now))
        return enabled
    }

    private data class CacheEntry(
        val enabled: Boolean,
        val loadedAtMs: Long,
    )
}
