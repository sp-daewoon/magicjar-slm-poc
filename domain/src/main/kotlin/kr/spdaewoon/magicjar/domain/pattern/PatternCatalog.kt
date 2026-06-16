package kr.spdaewoon.magicjar.domain.pattern

/**
 * 57 패턴 catalog (KIS Strategy Builder candlestick.py 65 PATTERN_DETECTORS - 9 제외).
 *
 * Source: pr-38-kis-pattern-raw-verification.md §2 매핑 LOCK + _workspace/strategy/pattern-catalog.md §57 LOCK
 * License: MIT
 *
 * 제외 9건:
 * - Lean 미지원 8: opening_marubozu · tweezer_top · tweezer_bottom · meeting_lines · matching_high
 *                  · deliberation · rising_three_methods · falling_three_methods
 * - 통합 중복 1: side_by_side_white_lines (gap_side_by_side_white로 통합)
 *
 * R1 active 10 (실제 매칭): doji · hammer · hanging_man · inverted_hammer · shooting_star
 *                          · engulfing · morning_star · evening_star · three_white_soldiers · three_black_crows
 * R2 stub 47 (Direction.NEUTRAL 반환 — R2 사이클 본문 port 후 활성화)
 */
object PatternCatalog {
    /**
     * 패턴 spec entry.
     *
     * @param id 패턴 id (KIS pattern_id 정합 — `dojistar` → `doji_star` rename)
     * @param category SINGLE / DOUBLE / TRIPLE_PLUS
     * @param minCandles KIS L48 정합 최소 캔들 수 (default 5)
     * @param defaultDirection 기본 방향성 (detector 내부 동적 결정 시 NEUTRAL)
     */
    data class Spec(
        val id: String,
        val category: String,
        val minCandles: Int = 5,
        val defaultDirection: Direction,
    )

    /** 57 패턴 LOCK — pr-38-kis-pattern-raw-verification.md §2 1:1 정합. */
    val ALL: List<Spec> =
        listOf(
            // ===== Single 14 (KIS L81-L240) =====
            Spec("doji", "SINGLE", 5, Direction.NEUTRAL),
            Spec("dragonfly_doji", "SINGLE", 5, Direction.BULL),
            Spec("gravestone_doji", "SINGLE", 5, Direction.BEAR),
            Spec("long_legged_doji", "SINGLE", 5, Direction.NEUTRAL),
            Spec("hammer", "SINGLE", 5, Direction.BULL),
            Spec("hanging_man", "SINGLE", 5, Direction.BEAR),
            Spec("inverted_hammer", "SINGLE", 5, Direction.BULL),
            Spec("shooting_star", "SINGLE", 5, Direction.BEAR),
            Spec("marubozu", "SINGLE", 5, Direction.NEUTRAL),
            Spec("closing_marubozu", "SINGLE", 5, Direction.NEUTRAL),
            Spec("spinning_top", "SINGLE", 5, Direction.NEUTRAL),
            Spec("belt_hold", "SINGLE", 5, Direction.NEUTRAL),
            Spec("high_wave", "SINGLE", 5, Direction.NEUTRAL),
            Spec("rickshaw_man", "SINGLE", 5, Direction.NEUTRAL),
            // ===== Double 16 (KIS L248-L443) =====
            Spec("engulfing", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("harami", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("harami_cross", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("piercing", "DOUBLE", 5, Direction.BULL),
            Spec("dark_cloud_cover", "DOUBLE", 5, Direction.BEAR),
            Spec("counterattack", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("on_neck", "DOUBLE", 5, Direction.BEAR),
            Spec("in_neck", "DOUBLE", 5, Direction.BEAR),
            Spec("thrusting", "DOUBLE", 5, Direction.BEAR),
            Spec("separating_lines", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("kicking", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("kicking_by_length", "DOUBLE", 5, Direction.NEUTRAL),
            Spec("matching_low", "DOUBLE", 5, Direction.BULL),
            Spec("gap_side_by_side_white", "DOUBLE", 5, Direction.BULL),
            Spec("homing_pigeon", "DOUBLE", 5, Direction.BULL),
            Spec("doji_star", "DOUBLE", 5, Direction.NEUTRAL),
            // ===== Triple+ 27 (KIS L451-L678) =====
            Spec("morning_star", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("morning_doji_star", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("evening_star", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("evening_doji_star", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("three_white_soldiers", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("three_black_crows", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("three_inside", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("three_outside", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("abandoned_baby", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("three_stars_in_south", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("advance_block", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("stalled_pattern", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("tasuki_gap", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("upside_gap_two_crows", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("three_line_strike", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("unique_three_river", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("breakaway", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("mat_hold", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("ladder_bottom", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("concealing_baby_swallow", "TRIPLE_PLUS", 5, Direction.BULL),
            Spec("stick_sandwich", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("tristar", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("identical_three_crows", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("two_crows", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("up_down_gap_three_methods", "TRIPLE_PLUS", 5, Direction.NEUTRAL),
            Spec("downside_tasuki_gap", "TRIPLE_PLUS", 5, Direction.BEAR),
            Spec("upside_tasuki_gap", "TRIPLE_PLUS", 5, Direction.BULL),
        )

    init {
        check(ALL.size == 57) { "PatternCatalog.ALL size mismatch: expected 57, actual ${ALL.size}" }
        val ids = ALL.map { it.id }
        check(ids.size == ids.toSet().size) { "PatternCatalog.ALL has duplicate ids" }
    }

    fun byId(id: String): Spec? = ALL.find { it.id == id }

    /** R1 active 10 — pr-38-kis-pattern-raw-verification.md §5 LOCK + 사용자 메시지 정합. */
    val R1_ACTIVE_IDS: Set<String> =
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
}
