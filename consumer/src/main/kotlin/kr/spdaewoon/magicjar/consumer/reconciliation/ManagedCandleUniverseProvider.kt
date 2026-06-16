package kr.spdaewoon.magicjar.consumer.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerOrderRequestJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class ManagedCandleUniverseEntry(
    val code: String,
    val sources: Set<String>,
    val priority: Int,
)

/**
 * ADR 0105 managed universe resolver.
 *
 * Watchlist is only one source. Boot reconciliation also includes holdings, open orders,
 * active explicit strategies, current realtime demands, and recent DETAIL/STRATEGY TTL snapshots.
 */
@Component
class ManagedCandleUniverseProvider(
    private val watchlistJpaRepository: ConsumerWatchlistJpaRepository,
    private val positionJpaRepository: PositionJpaRepository,
    private val orderRequestJpaRepository: ConsumerOrderRequestJpaRepository,
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val universeCache: ManagedCandleUniverseCache,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ManagedCandleUniverseProvider::class.java)

    companion object {
        private val CODE_REGEX = Regex("^\\d{6}$")
    }

    private data class MutableEntry(
        val code: String,
        val sources: MutableSet<String> = linkedSetOf(),
        var priority: Int,
    )

    fun resolve(): List<ManagedCandleUniverseEntry> {
        val entries = linkedMapOf<String, MutableEntry>()

        addCodes(entries, source = "holding", priority = 1) { positionJpaRepository.findDistinctCodesWithPositiveQuantity() }
        addCodes(entries, source = "open-order", priority = 2) { orderRequestJpaRepository.findOpenCodes() }
        addCodes(entries, source = "active-strategy", priority = 10) { activeStrategyCodes() }
        addCodes(entries, source = "realtime-active", priority = 15) { realtimeSubscriptionRouter.activeCodes().toList() }
        addCodes(entries, source = "detail-recent", priority = 20) { universeCache.recentDetailCodes().toList() }
        addCodes(entries, source = "watchlist", priority = 30) {
            watchlistJpaRepository.findAllByOrderByPriorityAscCreatedAtDesc().map { it.code }
        }
        addCodes(entries, source = "strategy-candidate", priority = 40) { universeCache.strategyCandidateCodes().toList() }

        return entries.values
            .map { ManagedCandleUniverseEntry(code = it.code, sources = it.sources.toSet(), priority = it.priority) }
            .sortedWith(compareBy<ManagedCandleUniverseEntry> { it.priority }.thenBy { it.code })
    }

    private fun addCodes(
        entries: MutableMap<String, MutableEntry>,
        source: String,
        priority: Int,
        supplier: () -> List<String>,
    ) {
        val codes =
            try {
                supplier()
            } catch (e: Exception) {
                log.warn("managed candle universe source 조회 실패: source={} err={}", source, e.message)
                emptyList()
            }

        codes
            .asSequence()
            .map { it.trim() }
            .filter { CODE_REGEX.matches(it) }
            .distinct()
            .forEach { code ->
                val entry = entries.getOrPut(code) { MutableEntry(code = code, priority = priority) }
                entry.sources.add(source)
                entry.priority = minOf(entry.priority, priority)
            }
    }

    private fun activeStrategyCodes(): List<String> =
        strategyJpaRepository
            .findActiveWithExplicitCodes()
            .flatMap { entity -> parseCodes(entity.codes) }
            .distinct()

    private fun parseCodes(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(json)
            when {
                node.isArray ->
                    node
                        .mapNotNull { child -> child.asText(null) }
                        .filter { CODE_REGEX.matches(it) }
                node.isTextual && CODE_REGEX.matches(node.asText()) -> listOf(node.asText())
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.warn("active strategy codes 파싱 실패: err={}", e.message)
            emptyList()
        }
    }
}
