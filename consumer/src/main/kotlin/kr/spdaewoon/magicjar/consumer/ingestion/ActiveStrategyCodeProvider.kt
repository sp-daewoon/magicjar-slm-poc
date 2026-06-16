package kr.spdaewoon.magicjar.consumer.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * ACTIVE/BUY_BLOCKED 전략의 명시 종목 코드 조회 단일 경로.
 *
 * 장중 전략 중지/비활성화는 다음 호출에서 바로 빠지므로 degraded polling이 오래 붙잡지 않는다.
 */
@Component
class ActiveStrategyCodeProvider(
    private val strategyJpaRepository: ConsumerStrategyJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ActiveStrategyCodeProvider::class.java)

    @Transactional(readOnly = true)
    fun activeCodesByStrategy(): Map<Long, Set<String>> =
        strategyJpaRepository
            .findActiveWithExplicitCodes()
            .mapNotNull { entity ->
                val id = entity.id ?: return@mapNotNull null
                val codes = parseCodes(entity.codes).toSet()
                if (codes.isEmpty()) null else id to codes
            }.toMap()

    @Transactional(readOnly = true)
    fun activeCodes(): Set<String> =
        activeCodesByStrategy()
            .values
            .flatten()
            .toSet()

    private fun parseCodes(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(json)
            when {
                node.isArray ->
                    node
                        .mapNotNull { child -> child.asText(null) }
                        .filter { CODE_REGEX.matches(it) }
                        .distinct()
                node.isTextual && CODE_REGEX.matches(node.asText()) -> listOf(node.asText())
                else -> emptyList()
            }
        } catch (e: Exception) {
            log.warn("ACTIVE strategy codes 파싱 실패: err={}", e.message)
            emptyList()
        }
    }

    private companion object {
        val CODE_REGEX = Regex("^\\d{6}$")
    }
}
