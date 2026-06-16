package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * [EntryPolicy] ↔ 평탄 Map 변환 단일 책임 codec — ADR 0114 §5 영속(R2-C2a).
 *
 * ## 의도 (진보 페르소나 DRY — ADR 0087)
 * 좌우명: "레거시는 빚이다. 이자가 붙기 전에 갚아라"
 *
 * `entry_policy` JSONB는 **api 쓰기 경로**(StrategyJpaAdapter.toEntity)와 **consumer 읽기 경로**
 * (ConsumerStrategyLookupJpaAdapter — BUY 가격 산정 입력)에서 양쪽 모두 직렬화/역직렬화가 필요하다.
 * 두 모듈이 동일 파싱 로직을 각자 복제하면 schema drift(한쪽만 필드 추가) 회귀 risk가 생긴다.
 * 본 codec을 도메인에 두어 **단일 진실원**으로 캡슐화한다(ScaleInPlan은 api 단독 deserialize라 미적용 — entry는 양 모듈 공유라 추출 합리).
 *
 * ## 순수성 (LOCK)
 * - Jackson·Spring import 0 — 도메인은 ObjectMapper에 의존하지 않는다(헥사고날 정합).
 * - caller(어댑터)가 자신의 ObjectMapper로 JSON String ↔ `Map<String, Any?>` 변환을 담당하고,
 *   본 codec은 Map ↔ [EntryPolicy] 도메인 변환만 책임진다(책임 경계 명확).
 * - 역직렬화 실패(타입 불일치·범위 위반 init)는 caller가 runCatching으로 흡수(safe* 패턴 정합).
 */
object EntryPolicySerde {
    const val KEY_MAX_ENTRY_SLIPPAGE_PCT = "maxEntrySlippagePct"
    const val KEY_ENTRY_TIMEOUT_SEC = "entryTimeoutSec"
    const val KEY_MAX_SPREAD_PCT = "maxSpreadPct"

    /**
     * [EntryPolicy] → 직렬화용 Map. null 필드는 키 자체를 생략(부분 override 표현 — `{ "maxSpreadPct": 0.5 }`).
     * 모든 필드가 null이면 빈 Map 반환(caller가 null로 보고 컬럼 미기록 가능).
     */
    fun toMap(policy: EntryPolicy): Map<String, Any> =
        buildMap {
            policy.maxEntrySlippagePct?.let { put(KEY_MAX_ENTRY_SLIPPAGE_PCT, it.toPlainString()) }
            policy.entryTimeoutSec?.let { put(KEY_ENTRY_TIMEOUT_SEC, it) }
            policy.maxSpreadPct?.let { put(KEY_MAX_SPREAD_PCT, it.toPlainString()) }
        }

    /**
     * Map → [EntryPolicy]. 빈 Map(모든 필드 부재)이면 null 반환(현행 = 전역 default).
     * 개별 필드 누락은 그 필드 null([EntryPolicy.resolved]가 전역 default로 폴백).
     *
     * 숫자·문자 양쪽 표현을 허용(jackson이 BigDecimal을 number 또는 string 으로 줄 수 있음).
     * [EntryPolicy.init] 범위 검증은 생성자에서 수행 — 위반 시 예외(caller runCatching 흡수).
     */
    fun fromMap(map: Map<String, Any?>?): EntryPolicy? {
        if (map.isNullOrEmpty()) return null
        val slippage = bigDecimalOrNull(map[KEY_MAX_ENTRY_SLIPPAGE_PCT])
        val timeout = intOrNull(map[KEY_ENTRY_TIMEOUT_SEC])
        val spread = bigDecimalOrNull(map[KEY_MAX_SPREAD_PCT])
        if (slippage == null && timeout == null && spread == null) return null
        return EntryPolicy(
            maxEntrySlippagePct = slippage,
            entryTimeoutSec = timeout,
            maxSpreadPct = spread,
        )
    }

    private fun bigDecimalOrNull(raw: Any?): BigDecimal? =
        when (raw) {
            null -> null
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
            else -> null
        }

    private fun intOrNull(raw: Any?): Int? =
        when (raw) {
            null -> null
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
}
