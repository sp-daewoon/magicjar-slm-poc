package kr.spdaewoon.magicjar.domain.strategyresearch

/**
 * 리서치 후보 전략 정의 (research-candidates-db).
 *
 * 기존 하드코딩 `CandidateSpec`의 Kotlin 람다(`entrySignal`)·`reasonFactory`를 제거하고,
 * DB(V74 `strategy_research_candidate`)에서 읽을 수 있는 순수 데이터로 표현한다.
 * 진입 평가는 [CandidateEntryEvaluator](entryDsl)로 위임한다.
 *
 * @property key 후보 고유 키 (UQ)
 * @property title 후보 노출명
 * @property marketFit 시장 적합성 설명
 * @property entryRules 사람이 읽는 진입 규칙 목록 (UI 표시용)
 * @property entryDsl 진입 DSL 식 — strategy-engine 평가기가 실행하는 권위 식
 * @property isBuiltin 코드/시드 기본 후보 여부 (false = 사용자 추가)
 */
data class ResearchCandidateDefinition(
    val key: String,
    val title: String,
    val marketFit: String,
    val entryRules: List<String>,
    val entryDsl: String,
    val isBuiltin: Boolean = true,
)

/**
 * 후보 정의 공급 포트.
 *
 * 기본 구현은 [BuiltinResearchCandidates] (코드 권위 = V74 시드 source).
 * api 모듈은 DB(`strategy_research_candidate`)에서 읽는 어댑터로 교체한다.
 */
fun interface ResearchCandidateProvider {
    fun candidates(): List<ResearchCandidateDefinition>
}

/**
 * 코드 권위 기본 후보 18종.
 *
 * V74 `strategy_research_candidate` 시드와 1:1 정합한다(시드 = 이 목록의 SQL 표현).
 *
 * ### DSL 권위 전환 주의 (동등성 보고 참조)
 * 기존 Kotlin 람다는 DSL이 표현할 수 없는 보조 조건(아래꼬리 비율·종가 위치·갭 폭 등)을 추가로
 * 검사했다. DB화 후에는 `entryDsl`이 권위이며, DSL 표현 한계로 인한 람다와의 진입 시점 차이는
 * 동등성 회귀 테스트로 측정해 보고한다. DSL이 표현 가능한 핵심 조건은 람다와 일치하도록 맞췄다.
 */
object BuiltinResearchCandidates : ResearchCandidateProvider {
    override fun candidates(): List<ResearchCandidateDefinition> = DEFINITIONS

    val DEFINITIONS: List<ResearchCandidateDefinition> =
        listOf(
            ResearchCandidateDefinition(
                key = "MA10_RECLAIM_SCALP",
                title = "MA10 회복 반등 스캘프",
                marketFit = "하락 추세 중 단기 평균선 회복 구간만 짧게 노립니다.",
                entryRules =
                    listOf(
                        "직전 candle 종가가 MA10 아래",
                        "현재 candle 종가가 MA10 위로 회복",
                        "현재 종가가 시가 이상",
                        "MA20 대비 과열 구간은 제외",
                    ),
                entryDsl = "close crosses_above ma(10) and close >= open and close <= ma(20) * 1.025",
            ),
            ResearchCandidateDefinition(
                key = "RSI30_REBOUND_SCALP",
                title = "RSI30 재진입 반등 스캘프",
                marketFit = "급락 뒤 과매도에서 빠져나오는 첫 반등만 잡습니다.",
                entryRules =
                    listOf(
                        "직전 RSI14가 30 이하",
                        "현재 RSI14가 30 위로 회복",
                        "현재 종가가 직전 종가보다 높음",
                        "거래량이 최근 20개 평균의 60% 이상",
                    ),
                entryDsl = "rsi(14) crosses_above 30 and close > prev_close and volume >= volume_ma(20) * 0.6",
            ),
            ResearchCandidateDefinition(
                key = "VOLUME_WASHOUT_REBOUND",
                title = "거래량 투매 후 반등 스캘프",
                marketFit = "투매성 음봉 뒤 저가 방어와 거래량 반등이 보일 때만 진입합니다.",
                entryRules =
                    listOf(
                        "직전 candle 종가 대비 약세 뒤 회복",
                        "거래량이 최근 20개 평균의 1.2배 이상",
                        "현재 candle이 양봉",
                        "현재 종가가 직전 종가를 회복",
                    ),
                entryDsl = "close > open and close >= prev_close and volume >= volume_ma(20) * 1.2",
            ),
            ResearchCandidateDefinition(
                key = "MA20_PULLBACK_CONTINUATION",
                title = "MA20 눌림목 추세 지속",
                marketFit = "중기 상승 흐름에서 MA20 근처 눌림 후 재상승하는 구간을 노립니다.",
                entryRules =
                    listOf(
                        "MA20이 MA50 위에 있음",
                        "현재 저가가 MA20 근처까지 눌림",
                        "현재 종가가 MA20 위로 회복",
                        "현재 candle이 양봉이고 거래량이 평균의 70% 이상",
                    ),
                entryDsl =
                    "ma(20) > ma(50) and close >= ma(20) and low <= ma(20) * 1.01 and " +
                        "close > open and volume >= volume_ma(20) * 0.7",
            ),
            ResearchCandidateDefinition(
                key = "MA5_20_GOLDEN_CROSS",
                title = "MA5·MA20 골든크로스",
                marketFit = "짧은 평균선이 중기 평균선을 상향 돌파하는 초기 반전 구간을 평가합니다.",
                entryRules =
                    listOf(
                        "직전 MA5가 MA20 이하",
                        "현재 MA5가 MA20 위로 돌파",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 80% 이상",
                    ),
                entryDsl = "ma(5) crosses_above ma(20) and close > open and volume >= volume_ma(20) * 0.8",
            ),
            ResearchCandidateDefinition(
                key = "BREAKOUT_20_HIGH_VOLUME",
                title = "20봉 고점 거래량 돌파",
                marketFit = "최근 박스권 상단을 거래량과 함께 돌파하는 모멘텀 구간을 봅니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 20개 candle 고점 위",
                        "거래량이 최근 20개 평균의 1.5배 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close crosses_above donchian_upper(20) and volume > volume_ma(20) * 1.5 and close > open",
            ),
            ResearchCandidateDefinition(
                key = "RSI40_TREND_PULLBACK",
                title = "상승 추세 RSI40 눌림 회복",
                marketFit = "MA50 위 상승 흐름에서 RSI40을 되찾는 눌림 회복 구간을 노립니다.",
                entryRules =
                    listOf(
                        "현재 종가가 MA50 위",
                        "직전 RSI14가 40 아래",
                        "현재 RSI14가 40 위로 회복",
                        "현재 candle이 양봉",
                    ),
                entryDsl = "close > ma(50) and rsi(14) crosses_above 40 and close > open",
            ),
            ResearchCandidateDefinition(
                key = "RSI50_MOMENTUM_RECLAIM",
                title = "RSI50 모멘텀 회복",
                marketFit = "중립선을 되찾으며 단기 모멘텀이 살아나는 종목을 평가합니다.",
                entryRules =
                    listOf(
                        "현재 종가가 MA20 위",
                        "직전 RSI14가 50 아래",
                        "현재 RSI14가 50 위로 회복",
                        "거래량이 최근 20개 평균의 80% 이상",
                    ),
                entryDsl = "close > ma(20) and rsi(14) crosses_above 50 and volume >= volume_ma(20) * 0.8",
            ),
            ResearchCandidateDefinition(
                key = "RANGE_SUPPORT_BOUNCE",
                title = "박스권 하단 지지 반등",
                marketFit = "최근 저점대 근처에서 저가 방어 후 반등하는 평균회귀 구간을 봅니다.",
                entryRules =
                    listOf(
                        "현재 저가가 직전 20개 candle 저점 근처",
                        "현재 candle이 양봉",
                        "현재 종가가 직전 종가보다 높음",
                        "RSI14가 35 이상 60 이하",
                    ),
                entryDsl =
                    "low <= donchian_lower(20) * 1.01 and close > open and close > prev_close and " +
                        "rsi(14) >= 35 and rsi(14) <= 60",
            ),
            ResearchCandidateDefinition(
                key = "BB_LOWER_RECLAIM",
                title = "볼린저 하단 회복 반등",
                marketFit = "볼린저 하단 이탈 후 다시 밴드 안으로 회복하는 과매도 반등을 봅니다.",
                entryRules =
                    listOf(
                        "직전 종가가 볼린저 하단 아래",
                        "현재 종가가 볼린저 하단 위로 회복",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 70% 이상",
                    ),
                entryDsl = "close crosses_above bb_lower(20, 2) and close > open and volume >= volume_ma(20) * 0.7",
            ),
            ResearchCandidateDefinition(
                key = "DONCHIAN10_BREAKOUT",
                title = "10봉 단기 돌파",
                marketFit = "짧은 박스권을 빠르게 벗어나는 단기 추세 시동을 평가합니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 10개 candle 고점 위",
                        "거래량이 최근 20개 평균의 1.2배 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close crosses_above donchian_upper(10) and volume > volume_ma(20) * 1.2 and close > open",
            ),
            ResearchCandidateDefinition(
                key = "VOLUME_SURGE_GREEN",
                title = "거래량 급증 장대양봉",
                marketFit = "거래량이 평소보다 크게 터지며 강한 양봉이 나온 구간을 추적합니다.",
                entryRules =
                    listOf(
                        "거래량이 최근 20개 평균의 2배 이상",
                        "현재 candle 수익률이 0.8% 이상",
                        "현재 candle이 양봉",
                        "종가가 candle 상단부에 위치",
                    ),
                entryDsl = "close > open and volume > volume_ma(20) * 2 and close >= open * 1.008",
            ),
            ResearchCandidateDefinition(
                key = "LOW_VOLUME_PULLBACK_BREAK",
                title = "저거래 눌림 후 전봉 돌파",
                marketFit = "거래가 말라붙은 눌림 이후 직전 고가를 회복하는 조용한 재시동을 봅니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 고가 위",
                        "현재 종가가 MA20 위",
                        "현재 거래량이 평균의 1.1배 이상",
                        "직전 거래가 마른 구간",
                    ),
                entryDsl = "close crosses_above donchian_upper(2) and close > ma(20) and volume >= volume_ma(20) * 1.1",
            ),
            ResearchCandidateDefinition(
                key = "THREE_BAR_RECOVERY",
                title = "3연속 종가 회복",
                marketFit = "작은 반등이 3개 candle 연속 이어질 때 추세 회복 가능성을 봅니다.",
                entryRules =
                    listOf(
                        "최근 종가가 연속 상승",
                        "현재 종가가 MA10 위",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 70% 이상",
                    ),
                entryDsl = "consecutive(up) >= 2 and close > ma(10) and close > open and volume >= volume_ma(20) * 0.7",
            ),
            ResearchCandidateDefinition(
                key = "HAMMER_VOLUME_REVERSAL",
                title = "거래량 동반 망치형 반전",
                marketFit = "아래꼬리로 매도 압력을 흡수한 뒤 양봉 마감하는 반전 후보입니다.",
                entryRules =
                    listOf(
                        "망치형(아래꼬리 긴) 캔들",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균의 1.2배 이상",
                    ),
                entryDsl = "pattern(hammer) and close > open and volume >= volume_ma(20) * 1.2",
            ),
            ResearchCandidateDefinition(
                key = "GAP_DOWN_REVERSAL",
                title = "갭하락 회복 반전",
                marketFit = "시초 또는 구간 갭하락을 빠르게 메우는 강한 되돌림을 추적합니다.",
                entryRules =
                    listOf(
                        "시가가 직전 종가보다 낮게 출발",
                        "현재 종가가 직전 종가 근처까지 회복",
                        "현재 candle이 양봉",
                        "거래량이 최근 20개 평균 이상",
                    ),
                entryDsl = "close > open and open <= prev_close * 0.98 and close >= prev_close * 0.995 and volume >= volume_ma(20)",
            ),
            ResearchCandidateDefinition(
                key = "VOLATILITY_SQUEEZE_BREAKOUT",
                title = "변동성 수축 후 돌파",
                marketFit = "거래 범위가 작아진 뒤 단기 고점을 돌파하는 에너지 방출 구간을 봅니다.",
                entryRules =
                    listOf(
                        "현재 종가가 직전 10개 candle 고점 위",
                        "거래량이 최근 20개 평균 이상",
                        "현재 candle이 양봉",
                    ),
                entryDsl = "close crosses_above donchian_upper(10) and volume >= volume_ma(20) and close > open",
            ),
            ResearchCandidateDefinition(
                key = "MA50_TREND_DIP_RECLAIM",
                title = "MA50 상승 추세 MA20 재회복",
                marketFit = "큰 추세는 유지된 상태에서 MA20 이탈 후 빠르게 되찾는 눌림목을 평가합니다.",
                entryRules =
                    listOf(
                        "MA20이 MA50 위",
                        "직전 종가가 MA20 아래",
                        "현재 종가가 MA20 위로 회복",
                        "RSI14가 45 이상 65 이하",
                    ),
                entryDsl =
                    "ma(20) > ma(50) and close crosses_above ma(20) and close > open and " +
                        "rsi(14) >= 45 and rsi(14) <= 65",
            ),
        )
}
