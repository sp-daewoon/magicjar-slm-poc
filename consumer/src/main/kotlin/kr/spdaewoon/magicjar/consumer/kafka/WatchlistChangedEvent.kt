package kr.spdaewoon.magicjar.consumer.kafka

/**
 * watchlist.changed.v1 Kafka 메시지 페이로드 (PR-7 spec §4-3 정합).
 */
data class WatchlistChangedEvent(
    val userId: String,
    val code: String,
    val action: String, // "ADDED" | "REMOVED"
    val occurredAt: String,
)
