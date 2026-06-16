package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/**
 * user_account Spring Data JPA Repository.
 * existsAny() — OnboardingGuardFilter + onboarding status 조회 용.
 * findFirstUserId() — 단일 사용자 시스템 전용. OnboardingStatus 응답에 userId 포함 용.
 */
interface UserAccountJpaRepository : JpaRepository<UserAccountJpaEntity, String> {
    @Query("SELECT COUNT(u) > 0 FROM UserAccountJpaEntity u")
    fun existsAny(): Boolean

    @Query("SELECT u.userId FROM UserAccountJpaEntity u ORDER BY u.createdAt ASC")
    fun findFirstUserIds(pageable: org.springframework.data.domain.Pageable): List<String>
}
