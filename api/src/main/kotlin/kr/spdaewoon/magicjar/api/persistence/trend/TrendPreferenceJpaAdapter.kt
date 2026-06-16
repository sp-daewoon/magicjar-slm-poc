package kr.spdaewoon.magicjar.api.persistence.trend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.trend.TrendPreference
import kr.spdaewoon.magicjar.domain.trend.TrendPreferenceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * TrendPreferenceRepository 도메인 포트 구현 (api 모듈 단독 — ADR 0101 §2-1-C 정합).
 *
 * ## JSONB ↔ Kotlin 변환 단일 진입점
 * - `trendMaPeriodsJson` JSONB array ↔ `List<Int>`
 * - `trendCrossPairJson` JSONB object ↔ `Pair<Int, Int>?`
 * - `extraJson` JSONB object ↔ `Map<String, Any?>?`
 *
 * ## 헌법 §6 (ADR 0051) — Repository default fun 함정 회피
 * UPSERT 로직 본 class private fun (`upsertInternal` · `entityToDomain` · `domainToEntityFields`) 단독.
 * Repository interface 는 abstract fun 만 (body 0).
 */
@Component
class TrendPreferenceJpaAdapter(
    private val jpa: UserPreferenceJpaRepository,
    private val objectMapper: ObjectMapper,
) : TrendPreferenceRepository {
    private val log = LoggerFactory.getLogger(TrendPreferenceJpaAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findByUserId(userId: String): TrendPreference? = jpa.findById(userId).map { entityToDomain(it) }.orElse(null)

    @Transactional
    override fun upsert(pref: TrendPreference): TrendPreference = upsertInternal(pref)

    // --- private helpers ---

    private fun upsertInternal(pref: TrendPreference): TrendPreference {
        val now = Instant.now()
        val existing = jpa.findById(pref.userId).orElse(null)
        val entity =
            if (existing != null) {
                existing.trendMaPeriodsJson = objectMapper.writeValueAsString(pref.maPeriods)
                existing.trendRsPeriod = pref.rsPeriod
                existing.trendCrossPairJson =
                    pref.crossPair?.let { (s, l) ->
                        objectMapper.writeValueAsString(mapOf("short" to s, "long" to l))
                    }
                existing.extraJson = pref.extra?.let { objectMapper.writeValueAsString(it) }
                existing.updatedAt = now
                existing
            } else {
                UserPreferenceJpaEntity(
                    userId = pref.userId,
                    trendMaPeriodsJson = objectMapper.writeValueAsString(pref.maPeriods),
                    trendRsPeriod = pref.rsPeriod,
                    trendCrossPairJson =
                        pref.crossPair?.let { (s, l) ->
                            objectMapper.writeValueAsString(mapOf("short" to s, "long" to l))
                        },
                    extraJson = pref.extra?.let { objectMapper.writeValueAsString(it) },
                    createdAt = now,
                    updatedAt = now,
                )
            }
        val saved = jpa.save(entity)
        log.debug("user_preference upsert: userId={} periods={} rs={}", pref.userId, pref.maPeriods, pref.rsPeriod)
        return entityToDomain(saved)
    }

    private fun entityToDomain(entity: UserPreferenceJpaEntity): TrendPreference {
        val periods: List<Int> = objectMapper.readValue(entity.trendMaPeriodsJson)
        val crossPair: Pair<Int, Int>? =
            entity.trendCrossPairJson?.let { json ->
                val map: Map<String, Int> = objectMapper.readValue(json)
                val s = map["short"] ?: return@let null
                val l = map["long"] ?: return@let null
                s to l
            }
        val extra: Map<String, Any?>? =
            entity.extraJson?.let { json -> objectMapper.readValue(json) }
        return TrendPreference(
            userId = entity.userId,
            maPeriods = periods,
            rsPeriod = entity.trendRsPeriod,
            crossPair = crossPair,
            updatedAt = entity.updatedAt,
            extra = extra,
        )
    }
}
