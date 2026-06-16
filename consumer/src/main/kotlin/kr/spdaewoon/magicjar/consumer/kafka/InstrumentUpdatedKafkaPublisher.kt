package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * instrument.updated.v1 Kafka topic publisher (T-IM1 PR-11a).
 *
 * payload: {changes: [{code, action: "INSERT"|"UPDATE"|"DELIST", market, name}, ...], syncedAt: ISO8601}
 * partition key = market (병렬 처리 — KOSPI·KOSDAQ 분리)
 *
 * dispatch-spec §5-4 payload 정합.
 */
@Component
class InstrumentUpdatedKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(InstrumentUpdatedKafkaPublisher::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Data.INSTRUMENT_UPDATED
    }

    /**
     * sync 변경 목록 publish.
     * @param changes 변경 항목 목록 (INSERT·UPDATE·DELIST)
     * @param syncedAt sync 완료 시각
     */
    fun publish(
        changes: List<InstrumentChange>,
        syncedAt: Instant = Instant.now(),
    ) {
        if (changes.isEmpty()) {
            log.debug("[instrument-kafka] 변경 없음 — publish skip")
            return
        }

        // market별 그룹핑 후 partition key로 전송
        val byMarket = changes.groupBy { it.market }
        for ((market, marketChanges) in byMarket) {
            try {
                val payload =
                    mapOf(
                        "syncedAt" to syncedAt.toString(),
                        "changes" to
                            marketChanges.map { c ->
                                mapOf(
                                    "code" to c.code,
                                    "action" to c.action.name,
                                    "market" to c.market,
                                    "name" to c.name,
                                )
                            },
                    )
                val json = objectMapper.writeValueAsString(payload)
                kafkaTemplate.send(TOPIC, market, json)
                log.debug("[instrument-kafka] publish market={} changes={}", market, marketChanges.size)
            } catch (e: Exception) {
                log.error(
                    "[instrument-kafka] publish 실패 market={} err={}",
                    market,
                    e.message,
                )
            }
        }

        log.info("[instrument-kafka] instrument.updated.v1 publish 완료 — total changes={}", changes.size)
    }
}

/**
 * instrument 변경 항목.
 */
data class InstrumentChange(
    val code: String,
    val action: InstrumentAction,
    val market: String,
    val name: String,
)

/**
 * instrument 변경 액션.
 */
enum class InstrumentAction {
    INSERT,
    UPDATE,
    DELIST,
}
