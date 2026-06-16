package kr.spdaewoon.magicjar.domain.watchlist

import kotlinx.datetime.Instant

/**
 * 관심종목 도메인 (ADR 0028·0040).
 * priority = 사용자 지정 우선순위 (낮은 값 = 높은 우선순위).
 */
data class WatchlistEntry(
    val id: Long? = null,
    val userId: String,
    val code: String,
    val priority: Int = 0,
    val createdAt: Instant,
)
