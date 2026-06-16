package kr.spdaewoon.magicjar.api.service.trend

import kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * trend / market 트랙 controller 가 현재 사용자 id 조회용 helper (single-tenant 1차 — WatchlistService 패턴 1:1).
 *
 * Spring Security 도입 시 본 helper 가 `@AuthenticationPrincipal` 단일 진입점으로 전환 — controller 변경 0.
 */
@Component
class TrendUserResolver(
    private val userAccountJpaRepository: UserAccountJpaRepository,
) {
    /** 현재 사용자 id 반환 — 미존재 시 401 ResponseStatusException. */
    fun currentUserId(): String =
        userAccountJpaRepository
            .findAll(Sort.by(Sort.Direction.ASC, "createdAt"))
            .firstOrNull()
            ?.userId
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "셋업 완료 후 사용 가능합니다 (ONBOARDING_REQUIRED)",
            )
}
