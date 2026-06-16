package kr.spdaewoon.magicjar.api.infrastructure.naver

import kr.spdaewoon.magicjar.api.persistence.NewsArticleJpaEntity
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
 * Naver 뉴스 신규 이벤트 Kafka Producer (T-NEWS Phase 2 — ADR 0075 §3 LOCK).
 *
 * 토픽    : news.collected.v1 (retention 7d — KafkaTopicConfig.newsCollectedTopic)
 * 파티션  : stock_code 기반 (종목별 최신순 ordering 가능성 고려)
 * 보관    : 7d (T-AI prompt input 재처리 + 사용자 검색 추적 — ADR 0075 §3-1)
 *
 * 멱등성 (ADR 0075 §3-3-A):
 *   Redis `news:dedup:url:{url_hash}` SETNX TTL 24h.
 *   DB UQ 이후 2중 보호 — Kafka at-least-once 재처리 시 중복 publish 방지.
 *
 * 이벤트 스키마 v1 (ADR 0075 §3-3 LOCK):
 * {
 *   "eventId"     : "<uuid>",
 *   "source"      : "NAVER",
 *   "stockCode"   : "005930",
 *   "title"       : "삼성전자, 메모리 반도체 가격 인상 검토",
 *   "bodySummary" : "삼성전자가 메모리 반도체 가격 인상을...",
 *   "url"         : "https://n.news.naver.com/article/001/0012345678",
 *   "urlHash"     : "a3f5b1...64자",
 *   "publishedAt" : "2026-05-15T08:50:00+09:00",
 *   "occurredAt"  : "2026-05-15T09:31:21+09:00",
 *   "schemaVersion": 1
 * }
 *
 * // AI 호출 0 건 (ADR 0075 §11-2 정합)
 */
@Component
class NewsKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(NewsKafkaProducer::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Data.NEWS_COLLECTED

        private val DEDUP_TTL = Duration.ofHours(24)

        private val KST = ZoneId.of("Asia/Seoul")
    }

    /**
     * 신규 뉴스 이벤트 발행.
     *
     * Redis SETNX 통과 시에만 Kafka publish (24h TTL dedup).
     * at-least-once 재시도 시 중복 publish 방지.
     *
     * @param entity 방금 DB에 저장된 NewsArticleJpaEntity
     */
    fun publish(entity: NewsArticleJpaEntity) {
        val dedupKey = RedisKeyRegistry.News.urlDedup(entity.urlHash)

        // Redis SETNX 24h — 이미 발행된 url_hash면 skip
        val acquired = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_TTL) ?: false
        if (!acquired) {
            log.debug("[NewsKafka] dedup skip — urlHash={}", entity.urlHash)
            return
        }

        val eventId = UUID.randomUUID().toString()
        val occurredAt = OffsetDateTime.now(KST)
        val publishedAt = OffsetDateTime.ofInstant(entity.publishedAt, KST)

        val payload =
            buildString {
                append("""{"eventId":"$eventId"""")
                append(""","source":"${entity.source}"""")
                append(""","stockCode":"${entity.stockCode}"""")
                append(""","title":"${escapeJson(entity.title)}"""")
                val summary = entity.bodySummary
                if (summary != null) {
                    append(""","bodySummary":"${escapeJson(summary)}"""")
                } else {
                    append(""","bodySummary":null""")
                }
                append(""","url":"${escapeJson(entity.url)}"""")
                append(""","urlHash":"${entity.urlHash}"""")
                append(""","publishedAt":"$publishedAt"""")
                append(""","occurredAt":"$occurredAt"""")
                append(""","schemaVersion":1}""")
            }

        val partitionKey = entity.stockCode

        kafkaTemplate
            .send(TOPIC, partitionKey, payload)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "[NewsKafka] publish 실패 — stockCode={} urlHash={} err={}",
                        entity.stockCode,
                        entity.urlHash,
                        ex.message,
                    )
                    // Redis dedup 키 rollback (publish 실패 시 재시도 허용)
                    redisTemplate.delete(dedupKey)
                } else {
                    log.debug(
                        "[NewsKafka] publish OK — stockCode={} urlHash={} partition={}",
                        entity.stockCode,
                        entity.urlHash,
                        result?.recordMetadata?.partition(),
                    )
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
