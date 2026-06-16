package kr.spdaewoon.magicjar.domain.trend

/**
 * 사용자 추세 설정 도메인 포트 (헥사고날 — ADR 0101 §2-1-B).
 *
 * impl 위치: `api` 모듈 `TrendPreferenceJpaAdapter` (ADR 0101 §2-1-C 정합 — user_account 패턴 1:1).
 *
 * ## 함정 회피 (ADR 0051 + 헌법 §6)
 * Spring Data JPA Repository interface 안 body 가진 fun 0건 LOCK —
 * caller class 의 private fun helper 또는 extension 으로 위임 (예: `TrendPreferenceJpaAdapter.upsertInternal`).
 */
interface TrendPreferenceRepository {
    /** 사용자 ID 로 조회. 없으면 null (service layer 가 default 합성 책임). */
    fun findByUserId(userId: String): TrendPreference?

    /**
     * UPSERT — 신규 row 또는 기존 row 갱신. `updatedAt` 은 adapter 가 `Instant.now()` 자동 갱신.
     *
     * @return 영속 후 (updatedAt 갱신된) TrendPreference
     */
    fun upsert(pref: TrendPreference): TrendPreference
}
