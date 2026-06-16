package kr.spdaewoon.magicjar.domain.market.port

/**
 * 종목별 시세 수집 source 추적 포트 (ADR 0048 §3-5).
 * Redis HASH: watchlist:source:{userId} — field=code, value=live|polling|idle
 * TTL 24h.
 */
interface SourceTracker {
    fun set(
        userId: String,
        code: String,
        source: String,
    )

    fun get(
        userId: String,
        code: String,
    ): String?

    fun remove(
        userId: String,
        code: String,
    )

    fun listForUser(userId: String): Map<String, String>
}
