package kr.spdaewoon.magicjar.api.infrastructure.dart

import kr.spdaewoon.magicjar.api.persistence.DisclosureJpaEntity
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * DART 공시 신규 이벤트 Kafka Producer (T-NEWS Phase 1 — ADR 0075 §3 LOCK).
 *
 * 토픽    : disclosure.new.v1 (retention 7d — KafkaTopicConfig.disclosureNewTopic)
 * 파티션  : corp_code 기반 (종목별 ordering 불필요 — rcept_no 멱등성으로 충분)
 * 보관    : 7d (운영 추적 + 정정공시 P0 알림 — ADR 0044 reuse 준비)
 *
 * 멱등성 (ADR 0075 §3-3-A):
 *   Redis `news:dedup:rcept:{rcept_no}` SETNX TTL 24h.
 *   DB UQ 이후 2중 보호 — Kafka at-least-once 재처리 시 중복 publish 방지.
 *
 * 이벤트 스키마 v1 (ADR 0075 §3-3 LOCK):
 * {
 *   "eventId"     : "<uuid>",
 *   "rceptNo"     : "20260515000123",
 *   "corpCode"    : "00126380",
 *   "stockCode"   : "005930",       -- null 허용 (비상장 공시)
 *   "rceptDt"     : "20260515",
 *   "corpName"    : "삼성전자",
 *   "reportNm"    : "[기재정정]사업보고서",
 *   "isCorrection": true,
 *   "url"         : "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515000123",
 *   "occurredAt"  : "2026-05-15T09:31:21+09:00",
 *   "schemaVersion": 1
 * }
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Component
class DisclosureKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(DisclosureKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Data.DISCLOSURE_NEW

        private val DEDUP_TTL = Duration.ofHours(24)
    }

    /**
     * 신규 공시 이벤트 발행.
     *
     * Redis SETNX 통과 시에만 Kafka publish (24h TTL dedup).
     * at-least-once 재시도 시 중복 publish 방지.
     *
     * @param entity 방금 DB에 저장된 DisclosureJpaEntity
     */
    fun publish(entity: DisclosureJpaEntity) {
        val dedupKey = RedisKeyRegistry.News.disclosureDedup(entity.rceptNo)

        // Redis SETNX 24h — 이미 발행된 rcept_no면 skip
        val acquired = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL) ?: false
        if (!acquired) {
            log.debug("[DisclosureKafka] dedup skip — rceptNo={}", entity.rceptNo)
            return
        }

        val eventId = UUID.randomUUID().toString()
        val occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
        val stockCodeJson = if (entity.stockCode != null) "\"${entity.stockCode}\"" else "null"

        val payload =
            buildString {
                append("""{"eventId":"$eventId"""")
                append(""","rceptNo":"${entity.rceptNo}"""")
                append(""","corpCode":"${entity.corpCode}"""")
                append(""","stockCode":$stockCodeJson""")
                append(""","rceptDt":"${entity.rceptDt}"""")
                append(""","corpName":"${escapeJson(entity.corpName)}"""")
                append(""","reportNm":"${escapeJson(entity.reportNm)}"""")
                append(""","isCorrection":${entity.isCorrection}""")
                append(""","url":"${entity.url}"""")
                append(""","occurredAt":"$occurredAt"""")
                append(""","schemaVersion":1}""")
            }

        val partitionKey = entity.corpCode

        kafkaTemplate
            .send(TOPIC, partitionKey, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "[DisclosureKafka] publish 실패 — rceptNo={} isCorrection={} err={}",
                        entity.rceptNo,
                        entity.isCorrection,
                        ex.message,
                    )
                    // Redis dedup 키 rollback (publish 실패 시 재시도 허용)
                    redisTemplate.delete(dedupKey)
                } else {
                    if (entity.isCorrection) {
                        log.warn(
                            "[DartDisclosurePolling] 정정공시 detect — rceptNo={} corpName={} reportNm={} partition={}",
                            entity.rceptNo,
                            entity.corpName,
                            entity.reportNm,
                            result?.recordMetadata?.partition(),
                        )
                    } else {
                        log.debug(
                            "[DisclosureKafka] publish OK — rceptNo={} corpCode={} partition={}",
                            entity.rceptNo,
                            entity.corpCode,
                            result?.recordMetadata?.partition(),
                        )
                    }
                }
            }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** JSON 특수문자 이스케이프 (큰따옴표·역슬래시·제어문자) */
    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
