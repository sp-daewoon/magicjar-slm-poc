package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.position.Position

/**
 * 포지션 Redis 캐시 포트 (ADR 0042 §Position Bootstrap 정합).
 * 부팅 시 KIS 잔고 조회 후 Redis에 적재 — 구현은 api/consumer 어댑터에서.
 */
interface PositionCachePort {
    fun getAll(userId: String): List<Position>

    fun put(position: Position)

    fun putAll(
        userId: String,
        positions: List<Position>,
    )

    fun evict(
        userId: String,
        code: String,
    )
}
