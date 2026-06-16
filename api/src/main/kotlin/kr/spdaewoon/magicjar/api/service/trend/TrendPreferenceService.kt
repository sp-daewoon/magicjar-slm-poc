package kr.spdaewoon.magicjar.api.service.trend

import kr.spdaewoon.magicjar.domain.trend.TrendPreference
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceDefaults
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceRepository
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * TrendPreference 조회·검증·적용 단일 진입점 (ADR 0101 §2-3).
 *
 * - `getOrDefault(userId)` — 사용자 row 없으면 [TrendPreferenceDefaults.forUser] 반환 (`isUserCustom=false` 의미).
 * - `update(userId, ...)` — TrendPreference invariant (require 5건) 위반 시 `IllegalArgumentException` throw.
 *
 * controller / MCP get_market_trend tool / AI prompt context 모두 본 service 단일 reuse.
 */
@Service
class TrendPreferenceService(
    private val repository: TrendPreferenceRepository,
) {
    /**
     * 사용자 현재 pref 또는 default 반환.
     *
     * 사용자 row 미존재 = 한 번도 PUT 한 적 없음 → default forUser(userId, EPOCH) 합성.
     */
    fun getOrDefault(userId: String): TrendPreference =
        repository.findByUserId(userId) ?: TrendPreferenceDefaults.forUser(userId, Instant.EPOCH)

    /**
     * pref 갱신 — Kotlin require 5건 의무 통과 시 UPSERT.
     *
     * 호출자가 이미 검증한 input 이라도 도메인 생성 시점 init {} 가 다시 검증 (이중 방어).
     */
    fun update(
        userId: String,
        maPeriods: List<Int>,
        rsPeriod: Int,
        crossPair: Pair<Int, Int>?,
        extra: Map<String, Any?>? = null,
    ): TrendPreference {
        val pref =
            TrendPreference(
                userId = userId,
                maPeriods = maPeriods,
                rsPeriod = rsPeriod,
                crossPair = crossPair,
                updatedAt = Instant.now(),
                extra = extra,
            )
        return repository.upsert(pref)
    }
}
