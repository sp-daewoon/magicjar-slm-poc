package kr.spdaewoon.magicjar.api.service.risk

import kr.spdaewoon.magicjar.api.persistence.position.PositionJpaAdapter
import kr.spdaewoon.magicjar.domain.position.Position
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/**
 * Position 조회 서비스 — T-RISK Phase 2 (ADR 0067 §5-4 G-1 + ADR 0042 정합).
 *
 * PositionController에서 사용. PositionJpaAdapter 직접 의존.
 * source 필터 등 추가 기능은 adapter 직접 사용.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Service
class PositionQueryService(
    private val positionJpaAdapter: PositionJpaAdapter,
) {
    private val log = LoggerFactory.getLogger(PositionQueryService::class.java)

    /**
     * 현재 보유 포지션 목록 (qty > 0) — source 필터 선택.
     *
     * @param userId 사용자 ID
     * @param source MOCK·REAL 필터 (null이면 전체)
     */
    @Transactional(readOnly = true)
    fun listCurrentByUser(
        userId: String,
        source: String? = null,
    ): List<Position> {
        log.debug("포지션 목록 조회: userId={} source={}", userId, source)
        return positionJpaAdapter.findByUserAndSource(userId, source)
    }

    /**
     * 포지션 단건 조회.
     */
    @Transactional(readOnly = true)
    fun get(
        userId: String,
        id: Long,
    ): Position {
        val position =
            positionJpaAdapter.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "position not found: id=$id")
        if (position.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "position not found: id=$id")
        }
        return position
    }
}
