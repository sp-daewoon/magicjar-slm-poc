package kr.spdaewoon.magicjar.pattern

import kr.spdaewoon.magicjar.domain.pattern.PatternCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PatternCatalog 57 LOCK 검증 — pr-38-kis-pattern-raw-verification.md §2 매핑 정합.
 *
 * spec §1-2 DoD #14·#18 정합.
 */
class PatternRegistryTest {
    @Test
    fun `catalog total — 57 patterns LOCK`() {
        assertEquals(57, PatternCatalog.ALL.size)
    }

    @Test
    fun `catalog — single 14 + double 16 + triple_plus 27 = 57`() {
        val byCat = PatternCatalog.ALL.groupBy { it.category }
        assertEquals(14, byCat["SINGLE"]?.size, "SINGLE count")
        assertEquals(16, byCat["DOUBLE"]?.size, "DOUBLE count")
        assertEquals(27, byCat["TRIPLE_PLUS"]?.size, "TRIPLE_PLUS count")
    }

    @Test
    fun `catalog — all ids unique`() {
        val ids = PatternCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate ids: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }}")
    }

    @Test
    fun `catalog — R1 active 10 IDs registered`() {
        val r1Expected =
            setOf(
                "doji",
                "hammer",
                "hanging_man",
                "inverted_hammer",
                "shooting_star",
                "engulfing",
                "morning_star",
                "evening_star",
                "three_white_soldiers",
                "three_black_crows",
            )
        assertEquals(r1Expected, PatternCatalog.R1_ACTIVE_IDS)
        // All R1 IDs must exist in catalog
        r1Expected.forEach { id ->
            assertTrue(PatternCatalog.byId(id) != null, "R1 id '$id' missing from catalog")
        }
    }

    @Test
    fun `catalog — Lean excluded 9 not in catalog`() {
        val excluded =
            setOf(
                "opening_marubozu",
                "tweezer_top",
                "tweezer_bottom",
                "meeting_lines",
                "matching_high",
                "deliberation",
                "rising_three_methods",
                "falling_three_methods",
                "side_by_side_white_lines",
            )
        excluded.forEach { id ->
            assertFalse(PatternCatalog.byId(id) != null, "Excluded id '$id' should NOT be in catalog")
        }
    }

    @Test
    fun `catalog — KIS dojistar renamed to doji_star`() {
        assertTrue(PatternCatalog.byId("doji_star") != null, "doji_star (renamed from KIS 'dojistar') must exist")
        assertFalse(PatternCatalog.byId("dojistar") != null, "Original KIS 'dojistar' should NOT exist (renamed)")
    }

    @Test
    fun `catalog — minCandles default 5 for all`() {
        PatternCatalog.ALL.forEach { spec ->
            assertEquals(5, spec.minCandles, "${spec.id} minCandles should be 5 (KIS L48 정합)")
        }
    }

    @Test
    fun `catalog — alias delegations registered (simple_triple x7 + tasuki_gap x3)`() {
        // simple_triple 7 detector
        val simpleTripleIds =
            setOf(
                "three_stars_in_south",
                "advance_block",
                "stalled_pattern",
                "unique_three_river",
                "breakaway",
                "mat_hold",
                "ladder_bottom",
                "concealing_baby_swallow",
            )
        simpleTripleIds.forEach { assertTrue(PatternCatalog.byId(it) != null, "$it missing") }
        // tasuki_gap alias 3 detector + base
        val tasukiIds = setOf("tasuki_gap", "up_down_gap_three_methods", "downside_tasuki_gap", "upside_tasuki_gap")
        tasukiIds.forEach { assertTrue(PatternCatalog.byId(it) != null, "$it missing") }
    }
}
