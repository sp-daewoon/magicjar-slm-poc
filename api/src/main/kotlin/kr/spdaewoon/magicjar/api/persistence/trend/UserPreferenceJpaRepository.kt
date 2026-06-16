package kr.spdaewoon.magicjar.api.persistence.trend

import org.springframework.data.jpa.repository.JpaRepository

/**
 * user_preference Spring Data JPA Repository.
 *
 * ## 헌법 §6 (ADR 0051) — Repository default fun 함정 회피
 * 본 interface 안 body 가진 fun 0건 LOCK — `findById`·`save` 등 JpaRepository inherited 만 사용.
 * UPSERT 헬퍼는 caller (`TrendPreferenceJpaAdapter`) private fun 이동.
 */
interface UserPreferenceJpaRepository : JpaRepository<UserPreferenceJpaEntity, String>
