package kr.spdaewoon.magicjar.domain.watchlist.port

import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry

/**
 * 관심종목 영속 포트 (ADR 0028·0040).
 */
interface WatchlistRepository {
    fun findByUserId(userId: String): List<WatchlistEntry>

    fun findByUserIdOrderByPriority(userId: String): List<WatchlistEntry>

    fun add(entry: WatchlistEntry): WatchlistEntry

    fun remove(
        userId: String,
        code: String,
    ): Boolean

    fun existsByUserIdAndCode(
        userId: String,
        code: String,
    ): Boolean
}
