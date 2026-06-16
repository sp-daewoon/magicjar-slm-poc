package kr.spdaewoon.magicjar.domain.indicator

/**
 * KIS 80지표 + magicJar 확장 지표 분류.
 */
enum class IndicatorCategory {
    TREND,
    MOMENTUM,
    VOLUME,
    VOLATILITY,
    OSCILLATOR,
    OTHER,
}

/**
 * 지표 메타데이터 (frontend IndicatorPicker · DSL · StrategyBuilder 의존).
 *
 * - [id]: 지표 고유 ID (예: "rsi"). 지표 카탈로그 정합.
 * - [displayName]: 한국어 표시 이름.
 * - [category]: 6 분류.
 * - [defaultParams]: 기본 파라미터 (예: {"period": 14}).
 * - [outputKeys]: [IndicatorPoint.values] map의 key 집합. multi-output 지표는 size > 1.
 */
data class IndicatorMetadata(
    val id: String,
    val displayName: String,
    val category: IndicatorCategory,
    val defaultParams: Map<String, Any>,
    val outputKeys: List<String>,
)

/**
 * 지표 카탈로그 (read-only).
 *
 * source-of-truth: `_workspace/strategy/indicator-catalog.md` (KIS indicators.py raw fetch 결과) + magicJar 확장 지표.
 * multi-output 매트릭스: spec §5 LOCK.
 *
 * ADR 0020 §3-1 + ADR 0021 §2-4 정합.
 */
object IndicatorCatalog {
    val ALL: List<IndicatorMetadata> =
        buildList {
            // ===== TREND 20개 =====
            add(IndicatorMetadata("ma", "이동평균(MA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("ma")))
            add(IndicatorMetadata("ema", "지수이동평균(EMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("ema")))
            add(IndicatorMetadata("wma", "가중이동평균(WMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("wma")))
            add(IndicatorMetadata("hma", "헐이동평균(HMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("hma")))
            add(IndicatorMetadata("dema", "이중지수이동평균(DEMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("dema")))
            add(IndicatorMetadata("tema", "삼중지수이동평균(TEMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("tema")))
            add(IndicatorMetadata("trima", "삼각이동평균(TRIMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("trima")))
            add(IndicatorMetadata("lwma", "선형가중이동평균(LWMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("lwma")))
            add(IndicatorMetadata("t3", "T3 이동평균", IndicatorCategory.TREND, mapOf("period" to 20, "volume_factor" to 0.7), listOf("t3")))
            add(IndicatorMetadata("zlema", "지연제거지수이동평균(ZLEMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("zlema")))
            add(IndicatorMetadata("kama", "카우프만적응이동평균(KAMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("kama")))
            add(
                IndicatorMetadata(
                    "alma",
                    "아르노-레그루 이동평균(ALMA)",
                    IndicatorCategory.TREND,
                    mapOf("period" to 20, "sigma" to 6.0, "offset" to 0.85),
                    listOf("alma"),
                ),
            )
            add(IndicatorMetadata("frama", "프랙탈적응이동평균(FRAMA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("frama")))
            add(IndicatorMetadata("vidya", "변동성 인덱스 이동평균(VIDYA)", IndicatorCategory.TREND, mapOf("period" to 20), listOf("vidya")))
            add(
                IndicatorMetadata(
                    "macd",
                    "MACD",
                    IndicatorCategory.TREND,
                    mapOf("fast" to 12, "slow" to 26, "signal" to 9),
                    listOf("macd", "signal", "histogram"),
                ),
            )
            add(IndicatorMetadata("ichimoku_tenkan", "이치모쿠 전환선", IndicatorCategory.TREND, mapOf("period" to 9), listOf("tenkan")))
            add(IndicatorMetadata("ichimoku_kijun", "이치모쿠 기준선", IndicatorCategory.TREND, mapOf("period" to 26), listOf("kijun")))
            add(
                IndicatorMetadata(
                    "supertrend",
                    "슈퍼트렌드",
                    IndicatorCategory.TREND,
                    mapOf("period" to 14, "multiplier" to 3.0),
                    listOf("value", "direction"),
                ),
            )
            add(IndicatorMetadata("sar", "포물선형 SAR", IndicatorCategory.TREND, mapOf("af_start" to 0.02, "af_max" to 0.2), listOf("sar")))
            add(IndicatorMetadata("regression_slope", "선형회귀 기울기", IndicatorCategory.TREND, mapOf("period" to 20), listOf("slope")))

            // ===== MOMENTUM 20개 =====
            add(IndicatorMetadata("rsi", "RSI", IndicatorCategory.MOMENTUM, mapOf("period" to 14), listOf("rsi")))
            add(IndicatorMetadata("stochastic_k", "스토캐스틱 %K", IndicatorCategory.MOMENTUM, mapOf("period" to 14), listOf("k")))
            add(
                IndicatorMetadata(
                    "stochastic_d",
                    "스토캐스틱 %D",
                    IndicatorCategory.MOMENTUM,
                    mapOf("k_period" to 14, "d_period" to 3),
                    listOf("k", "d"),
                ),
            )
            add(
                IndicatorMetadata(
                    "stochrsi",
                    "스토캐스틱 RSI",
                    IndicatorCategory.MOMENTUM,
                    mapOf("rsi_period" to 14, "stoch_period" to 14),
                    listOf("stochrsi"),
                ),
            )
            add(IndicatorMetadata("cci", "상품채널지수(CCI)", IndicatorCategory.MOMENTUM, mapOf("period" to 20), listOf("cci")))
            add(IndicatorMetadata("williams_r", "윌리엄스 %R", IndicatorCategory.MOMENTUM, mapOf("period" to 14), listOf("wr")))
            add(IndicatorMetadata("roc", "변화율(ROC)", IndicatorCategory.MOMENTUM, mapOf("period" to 10), listOf("roc")))
            add(IndicatorMetadata("momentum", "모멘텀", IndicatorCategory.MOMENTUM, mapOf("period" to 10), listOf("momentum")))
            add(IndicatorMetadata("apo", "절대가격오실레이터(APO)", IndicatorCategory.MOMENTUM, mapOf("fast" to 12, "slow" to 26), listOf("apo")))
            add(IndicatorMetadata("ppo", "퍼센트가격오실레이터(PPO)", IndicatorCategory.MOMENTUM, mapOf("fast" to 12, "slow" to 26), listOf("ppo")))
            add(IndicatorMetadata("cmo", "찬드 모멘텀 오실레이터(CMO)", IndicatorCategory.MOMENTUM, mapOf("period" to 14), listOf("cmo")))
            add(IndicatorMetadata("ao", "어썸 오실레이터(AO)", IndicatorCategory.MOMENTUM, emptyMap(), listOf("ao")))
            add(IndicatorMetadata("trix", "TRIX", IndicatorCategory.MOMENTUM, mapOf("period" to 15), listOf("trix")))
            add(IndicatorMetadata("tsi", "진정강도지수(TSI)", IndicatorCategory.MOMENTUM, mapOf("long" to 25, "short" to 13), listOf("tsi")))
            add(IndicatorMetadata("rvi", "상대활력지수(RVI)", IndicatorCategory.MOMENTUM, mapOf("period" to 10), listOf("rvi")))
            add(IndicatorMetadata("dpo", "차이가격오실레이터(DPO)", IndicatorCategory.MOMENTUM, mapOf("period" to 20), listOf("dpo")))
            add(IndicatorMetadata("kst", "Know Sure Thing(KST)", IndicatorCategory.MOMENTUM, emptyMap(), listOf("kst")))
            add(IndicatorMetadata("coppock", "코폭 커브", IndicatorCategory.MOMENTUM, emptyMap(), listOf("coppock")))
            add(
                IndicatorMetadata(
                    "schaff",
                    "샤프 추세 사이클(STC)",
                    IndicatorCategory.MOMENTUM,
                    mapOf("period" to 23, "fast" to 10, "slow" to 50),
                    listOf("schaff"),
                ),
            )
            add(IndicatorMetadata("fisher", "피셔 변환", IndicatorCategory.MOMENTUM, mapOf("period" to 9), listOf("fisher")))

            // ===== VOLUME 12개 =====
            add(IndicatorMetadata("obv", "에너지이동지표(OBV)", IndicatorCategory.VOLUME, emptyMap(), listOf("obv")))
            add(IndicatorMetadata("volume_ma", "거래량 이동평균", IndicatorCategory.VOLUME, mapOf("period" to 20), listOf("volume_ma")))
            add(IndicatorMetadata("vwap", "거래량가중평균가격(VWAP)", IndicatorCategory.VOLUME, emptyMap(), listOf("vwap")))
            add(IndicatorMetadata("mfi", "자금흐름지수(MFI)", IndicatorCategory.VOLUME, mapOf("period" to 14), listOf("mfi")))
            add(IndicatorMetadata("cmf", "차이킨 머니플로우(CMF)", IndicatorCategory.VOLUME, mapOf("period" to 20), listOf("cmf")))
            add(IndicatorMetadata("cho", "차이킨 오실레이터", IndicatorCategory.VOLUME, mapOf("fast" to 3, "slow" to 10), listOf("cho")))
            add(IndicatorMetadata("ad", "A/D 라인", IndicatorCategory.VOLUME, emptyMap(), listOf("ad")))
            add(IndicatorMetadata("adl", "누적/분배 라인(ADL)", IndicatorCategory.VOLUME, emptyMap(), listOf("adl")))
            add(IndicatorMetadata("force", "힘 지수(Force Index)", IndicatorCategory.VOLUME, mapOf("period" to 13), listOf("force")))
            add(IndicatorMetadata("vwma", "거래량가중이동평균(VWMA)", IndicatorCategory.VOLUME, mapOf("period" to 20), listOf("vwma")))
            add(IndicatorMetadata("eom", "시장 이동 편의(EOM)", IndicatorCategory.VOLUME, mapOf("period" to 14), listOf("eom")))
            add(IndicatorMetadata("kvo", "클링거 볼륨 오실레이터", IndicatorCategory.VOLUME, mapOf("fast" to 34, "slow" to 55), listOf("kvo")))

            // ===== VOLATILITY 12개 =====
            add(
                IndicatorMetadata(
                    "bb",
                    "볼린저 밴드",
                    IndicatorCategory.VOLATILITY,
                    mapOf("period" to 20, "std_dev" to 2.0),
                    listOf("upper", "middle", "lower"),
                ),
            )
            add(
                IndicatorMetadata(
                    "bb_width",
                    "볼린저 밴드 폭",
                    IndicatorCategory.VOLATILITY,
                    mapOf("period" to 20, "std_dev" to 2.0),
                    listOf("width"),
                ),
            )
            add(
                IndicatorMetadata(
                    "bb_percent",
                    "볼린저 밴드 %B",
                    IndicatorCategory.VOLATILITY,
                    mapOf("period" to 20, "std_dev" to 2.0),
                    listOf("percent"),
                ),
            )
            add(IndicatorMetadata("atr", "평균진실범위(ATR)", IndicatorCategory.VOLATILITY, mapOf("period" to 14), listOf("atr")))
            add(IndicatorMetadata("natr", "정규화 ATR", IndicatorCategory.VOLATILITY, mapOf("period" to 14), listOf("natr")))
            add(
                IndicatorMetadata(
                    "keltner_upper",
                    "켈트너 채널 상단",
                    IndicatorCategory.VOLATILITY,
                    mapOf(
                        "ema_period" to 20,
                        "atr_period" to 14,
                        "multiplier" to 2.0,
                    ),
                    listOf("upper"),
                ),
            )
            add(
                IndicatorMetadata(
                    "keltner_lower",
                    "켈트너 채널 하단",
                    IndicatorCategory.VOLATILITY,
                    mapOf(
                        "ema_period" to 20,
                        "atr_period" to 14,
                        "multiplier" to 2.0,
                    ),
                    listOf("lower"),
                ),
            )
            add(IndicatorMetadata("donchian_upper", "돈치안 채널 상단", IndicatorCategory.VOLATILITY, mapOf("period" to 20), listOf("upper")))
            add(IndicatorMetadata("donchian_lower", "돈치안 채널 하단", IndicatorCategory.VOLATILITY, mapOf("period" to 20), listOf("lower")))
            add(IndicatorMetadata("chop", "채핀 지수(Choppiness)", IndicatorCategory.VOLATILITY, mapOf("period" to 14), listOf("chop")))
            add(IndicatorMetadata("mass_index", "질량 지수(Mass Index)", IndicatorCategory.VOLATILITY, mapOf("period" to 25), listOf("mass")))
            add(IndicatorMetadata("augen", "오겐 변동성", IndicatorCategory.VOLATILITY, mapOf("period" to 14), listOf("augen")))

            // ===== OSCILLATOR 8개 =====
            add(IndicatorMetadata("adx", "ADX", IndicatorCategory.OSCILLATOR, mapOf("period" to 14), listOf("adx")))
            add(IndicatorMetadata("adxr", "ADXR", IndicatorCategory.OSCILLATOR, mapOf("period" to 14), listOf("adxr")))
            add(IndicatorMetadata("aroon_up", "아룬 상단", IndicatorCategory.OSCILLATOR, mapOf("period" to 25), listOf("up")))
            add(IndicatorMetadata("aroon_down", "아룬 하단", IndicatorCategory.OSCILLATOR, mapOf("period" to 25), listOf("down")))
            add(IndicatorMetadata("vortex_plus", "소용돌이 +VI", IndicatorCategory.OSCILLATOR, mapOf("period" to 14), listOf("plus")))
            add(IndicatorMetadata("vortex_minus", "소용돌이 -VI", IndicatorCategory.OSCILLATOR, mapOf("period" to 14), listOf("minus")))
            add(
                IndicatorMetadata(
                    "ultosc",
                    "최종 오실레이터(UO)",
                    IndicatorCategory.OSCILLATOR,
                    mapOf("p1" to 7, "p2" to 14, "p3" to 28),
                    listOf("uo"),
                ),
            )
            add(IndicatorMetadata("bop", "매수-매도 균형(BOP)", IndicatorCategory.OSCILLATOR, emptyMap(), listOf("bop")))

            // ===== OTHER 8개 + magicJar 확장 2개 =====
            add(IndicatorMetadata("std", "표준편차", IndicatorCategory.OTHER, mapOf("period" to 20), listOf("std")))
            add(IndicatorMetadata("variance", "분산", IndicatorCategory.OTHER, mapOf("period" to 20), listOf("variance")))
            add(IndicatorMetadata("disparity", "이격도", IndicatorCategory.OTHER, mapOf("period" to 20), listOf("disparity")))
            add(IndicatorMetadata("midpoint", "중간점", IndicatorCategory.OTHER, mapOf("period" to 14), listOf("midpoint")))
            add(IndicatorMetadata("midprice", "중간 가격", IndicatorCategory.OTHER, mapOf("period" to 14), listOf("midprice")))
            add(IndicatorMetadata("logr", "로그 수익률", IndicatorCategory.OTHER, emptyMap(), listOf("logr")))
            add(IndicatorMetadata("pivot", "피봇 포인트", IndicatorCategory.OTHER, emptyMap(), listOf("p", "r1", "r2", "r3", "s1", "s2", "s3")))
            add(IndicatorMetadata("regression_intercept", "선형회귀 절편", IndicatorCategory.OTHER, mapOf("period" to 20), listOf("intercept")))
            add(
                IndicatorMetadata(
                    "fvg",
                    "Fair Value Gap(FVG)",
                    IndicatorCategory.OTHER,
                    mapOf("mitigationPct" to 50),
                    listOf(
                        "fvg",
                        "bullish_active",
                        "bearish_active",
                        "bullish_top",
                        "bullish_bottom",
                        "bullish_mid",
                        "bullish_fill_pct",
                        "bearish_top",
                        "bearish_bottom",
                        "bearish_mid",
                        "bearish_fill_pct",
                    ),
                ),
            )
            add(
                IndicatorMetadata(
                    "order_block",
                    "오더블록(Order Block)",
                    IndicatorCategory.OTHER,
                    mapOf(
                        "swingLookback" to 20,
                        "searchLookback" to 20,
                        "mitigationPct" to 50,
                        "detection" to "bos",
                        "range" to "wick",
                    ),
                    listOf(
                        "order_block",
                        "bullish_active",
                        "bearish_active",
                        "bullish_top",
                        "bullish_bottom",
                        "bullish_mid",
                        "bullish_fill_pct",
                        "bearish_top",
                        "bearish_bottom",
                        "bearish_mid",
                        "bearish_fill_pct",
                        "bos_direction",
                    ),
                ),
            )
        }

    /**
     * 등록된 모든 spec ID 집합. KIS 80개 + magicJar 확장 2개.
     */
    val ALL_IDS: Set<String> = ALL.map { it.id }.toSet()

    fun findById(id: String): IndicatorMetadata? = ALL.firstOrNull { it.id == id }

    fun byCategory(category: IndicatorCategory): List<IndicatorMetadata> = ALL.filter { it.category == category }
}
