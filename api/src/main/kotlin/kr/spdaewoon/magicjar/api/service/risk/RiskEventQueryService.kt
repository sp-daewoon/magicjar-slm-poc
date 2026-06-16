package kr.spdaewoon.magicjar.api.service.risk

import kr.spdaewoon.magicjar.api.persistence.risk.RiskEventJpaAdapter
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * RiskEvent 조회 서비스 — T-RISK Phase 2 (ADR 0067 §5-2 G-1).
 *
 * RiskEventController에서 사용. RiskEventJpaAdapter 직접 의존.
 * 시계열 페이징 조회는 domain port 범위를 벗어나므로 adapter 직접 사용.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Service
class RiskEventQueryService(
    private val riskEventJpaAdapter: RiskEventJpaAdapter,
) {
    private val log = LoggerFactory.getLogger(RiskEventQueryService::class.java)

    /**
     * 사용자 시계열 risk_event 페이징 조회 (ts DESC).
     */
    @Transactional(readOnly = true)
    fun list(
        userId: String,
        from: Instant,
        to: Instant,
        page: Int,
        size: Int,
    ): Page<RiskEvent> {
        log.debug("risk_event 목록 조회: userId={} from={} to={} page={} size={}", userId, from, to, page, size)
        return riskEventJpaAdapter.findByUserAndRange(userId, from, to, page, size)
    }

    /**
     * risk_event 단건 조회.
     */
    @Transactional(readOnly = true)
    fun get(
        userId: String,
        id: Long,
    ): RiskEvent {
        val event =
            riskEventJpaAdapter.findById(id)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "risk_event not found: id=$id")
        if (event.userId != userId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "risk_event not found: id=$id")
        }
        return event
    }
}
