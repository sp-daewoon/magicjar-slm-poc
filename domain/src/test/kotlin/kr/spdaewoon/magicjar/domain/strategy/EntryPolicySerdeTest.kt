package kr.spdaewoon.magicjar.domain.strategy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [EntryPolicySerde] round-trip + null 하위호환 — ADR 0114 §5.
 */
class EntryPolicySerdeTest {
    @Test
    fun `전체 필드 round-trip — toMap → fromMap 동치`() {
        val policy =
            EntryPolicy(
                maxEntrySlippagePct = BigDecimal("0.25"),
                entryTimeoutSec = 20,
                maxSpreadPct = BigDecimal("0.4"),
            )
        val restored = EntryPolicySerde.fromMap(EntryPolicySerde.toMap(policy))
        assertEquals(policy, restored)
    }

    @Test
    fun `부분 override round-trip — null 필드는 키 생략 후 복원 시에도 null 유지`() {
        val policy = EntryPolicy(maxSpreadPct = BigDecimal("0.5"))
        val map = EntryPolicySerde.toMap(policy)
        assertEquals(setOf(EntryPolicySerde.KEY_MAX_SPREAD_PCT), map.keys)
        val restored = EntryPolicySerde.fromMap(map)
        assertEquals(policy, restored)
        assertNull(restored?.maxEntrySlippagePct)
        assertNull(restored?.entryTimeoutSec)
    }

    @Test
    fun `null 하위호환 — null map과 빈 map은 null EntryPolicy(=전역 default)`() {
        assertNull(EntryPolicySerde.fromMap(null))
        assertNull(EntryPolicySerde.fromMap(emptyMap()))
        assertNull(
            EntryPolicySerde.fromMap(
                mapOf(
                    EntryPolicySerde.KEY_MAX_ENTRY_SLIPPAGE_PCT to null,
                    EntryPolicySerde.KEY_ENTRY_TIMEOUT_SEC to null,
                ),
            ),
        )
    }

    @Test
    fun `숫자·문자 양쪽 표현 허용 — jackson number 또는 string 입력 둘 다 파싱`() {
        val fromString =
            EntryPolicySerde.fromMap(
                mapOf(
                    EntryPolicySerde.KEY_MAX_ENTRY_SLIPPAGE_PCT to "0.2",
                    EntryPolicySerde.KEY_ENTRY_TIMEOUT_SEC to "15",
                    EntryPolicySerde.KEY_MAX_SPREAD_PCT to "0.3",
                ),
            )
        val fromNumber =
            EntryPolicySerde.fromMap(
                mapOf(
                    EntryPolicySerde.KEY_MAX_ENTRY_SLIPPAGE_PCT to 0.2,
                    EntryPolicySerde.KEY_ENTRY_TIMEOUT_SEC to 15,
                    EntryPolicySerde.KEY_MAX_SPREAD_PCT to BigDecimal("0.3"),
                ),
            )
        assertTrue(fromString?.maxEntrySlippagePct?.compareTo(BigDecimal("0.2")) == 0)
        assertEquals(15, fromString?.entryTimeoutSec)
        assertTrue(fromNumber?.maxEntrySlippagePct?.compareTo(BigDecimal("0.2")) == 0)
        assertEquals(15, fromNumber?.entryTimeoutSec)
        assertTrue(fromNumber?.maxSpreadPct?.compareTo(BigDecimal("0.3")) == 0)
    }

    @Test
    fun `resolved — 전략별 override 우선, null 필드는 전역 default 폴백`() {
        val global = ResolvedEntryPolicy.DEFAULTS
        val resolved = EntryPolicy(maxSpreadPct = BigDecimal("0.6")).resolved(global)
        assertTrue(resolved.maxSpreadPct.compareTo(BigDecimal("0.6")) == 0)
        assertEquals(global.maxEntrySlippagePct, resolved.maxEntrySlippagePct)
        assertEquals(global.entryTimeoutSec, resolved.entryTimeoutSec)
    }
}
