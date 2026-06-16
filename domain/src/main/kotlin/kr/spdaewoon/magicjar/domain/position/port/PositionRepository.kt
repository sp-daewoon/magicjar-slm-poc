package kr.spdaewoon.magicjar.domain.position.port

import kr.spdaewoon.magicjar.domain.position.Position

/**
 * 포지션 영속 포트 (ADR 0042).
 */
interface PositionRepository {
    fun findByUserId(userId: String): List<Position>

    fun findByUserIdAndCode(
        userId: String,
        code: String,
    ): Position?

    fun upsert(position: Position): Position

    fun upsertAll(positions: List<Position>): Int
}
