package kr.spdaewoon.magicjar.domain.strategy

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [EntryPolicy] 단위 테스트 — ADR 0114 §5 진입 설정 3종 (U-1 묶음 객체).
 *
 * 핵심 계약: JSONB add-only · null=현행(전역 default) 하위호환 · 직렬화 round-trip.
 * (실 영속 매퍼는 R2 `StrategyEntityMapper`. 본 테스트는 도메인 serde 계약·검증·resolve를 고정한다.)
 */
class EntryPolicyTest {
    private val mapper =
        jacksonObjectMapper()
            // 기존 strategy JSONB에 entry_policy 모르는 키가 와도 무시(하위호환 — R2 매퍼 정합)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // --- 검증(init) ---

    @Test
    fun `accepts all-null (현행 동작 — 전역 default 폴백)`() {
        val p = EntryPolicy()
        assertNull(p.maxEntrySlippagePct)
        assertNull(p.entryTimeoutSec)
        assertNull(p.maxSpreadPct)
    }

    @Test
    fun `rejects negative slippage`() {
        assertThrows(IllegalArgumentException::class.java) { EntryPolicy(maxEntrySlippagePct = BigDecimal("-0.1")) }
    }

    @Test
    fun `rejects slippage over sanity cap`() {
        assertThrows(IllegalArgumentException::class.java) { EntryPolicy(maxEntrySlippagePct = BigDecimal("6")) }
    }

    @Test
    fun `rejects timeout out of range`() {
        assertThrows(IllegalArgumentException::class.java) { EntryPolicy(entryTimeoutSec = 0) }
        assertThrows(IllegalArgumentException::class.java) { EntryPolicy(entryTimeoutSec = 301) }
    }

    @Test
    fun `rejects negative spread`() {
        assertThrows(IllegalArgumentException::class.java) { EntryPolicy(maxSpreadPct = BigDecimal("-1")) }
    }

    // --- resolve (전역 default 폴백) ---

    @Test
    fun `resolved fills nulls from global default`() {
        val r = EntryPolicy(maxSpreadPct = BigDecimal("0.5")).resolved(ResolvedEntryPolicy.DEFAULTS)
        assertEquals(BigDecimal("0.2"), r.maxEntrySlippagePct) // null → 전역 default
        assertEquals(15, r.entryTimeoutSec) // null → 전역 default
        assertEquals(BigDecimal("0.5"), r.maxSpreadPct) // override 유지
    }

    @Test
    fun `resolved override wins over default on all fields`() {
        val custom = ResolvedEntryPolicy(BigDecimal("0.1"), 10, BigDecimal("0.25"))
        val r = EntryPolicy(BigDecimal("0.3"), 20, BigDecimal("0.4")).resolved(custom)
        assertEquals(ResolvedEntryPolicy(BigDecimal("0.3"), 20, BigDecimal("0.4")), r)
    }

    @Test
    fun `default constants match ADR 0114 §2-3`() {
        assertEquals(BigDecimal("0.2"), ResolvedEntryPolicy.DEFAULTS.maxEntrySlippagePct)
        assertEquals(15, ResolvedEntryPolicy.DEFAULTS.entryTimeoutSec)
        assertEquals(BigDecimal("0.3"), ResolvedEntryPolicy.DEFAULTS.maxSpreadPct)
    }

    // --- 직렬화 round-trip (3종) ---

    @Test
    fun `serialization round-trip preserves all three fields`() {
        val original = EntryPolicy(BigDecimal("0.2"), 15, BigDecimal("0.3"))
        val json = mapper.writeValueAsString(original)
        val back = mapper.readValue<EntryPolicy>(json)
        assertEquals(original, back)
    }

    @Test
    fun `partial JSON deserializes missing fields as null (add-only 하위호환)`() {
        // entry_policy에 maxSpreadPct만 있는 기존 row → 나머지 null(전역 default 폴백)
        val back = mapper.readValue<EntryPolicy>("""{"maxSpreadPct":0.5}""")
        assertNull(back.maxEntrySlippagePct)
        assertNull(back.entryTimeoutSec)
        assertEquals(BigDecimal("0.5"), back.maxSpreadPct)
    }

    @Test
    fun `empty JSON object deserializes to all-null policy`() {
        val back = mapper.readValue<EntryPolicy>("{}")
        assertEquals(EntryPolicy(), back)
    }

    // --- StrategyDefinition 하위호환 (entry_policy 없는 기존 전략) ---

    @Test
    fun `existing StrategyDefinition has null entryPolicy by default (zero-regression)`() {
        val def =
            StrategyDefinition(
                dsl =
                    kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition(
                        name = "legacy",
                        nameKo = "레거시",
                        buyConditionText = "RSI(14) < 30",
                        sellConditionText = "RSI(14) > 70",
                    ),
            )
        assertNull(def.entryPolicy)
    }
}
