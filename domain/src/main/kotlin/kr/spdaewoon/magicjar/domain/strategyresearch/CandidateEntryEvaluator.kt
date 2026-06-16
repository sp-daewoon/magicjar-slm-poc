package kr.spdaewoon.magicjar.domain.strategyresearch

/**
 * 후보 전략 진입 평가 포트 (research-candidates-db).
 *
 * ### 배경
 * 기존 [StrategyResearchEngine]는 후보별 진입 조건을 Kotlin 람다로 하드코딩했다.
 * 후보 전략을 DB로 관리하면서(V74 `strategy_research_candidate`) 람다 중복 구현을 제거하고,
 * 후보의 `entryDsl` 텍스트를 strategy-engine `StrategyEvaluator`로 평가하도록 전환한다.
 *
 * ### 모듈 경계 (ADR 0004 · ADR 0092)
 * - domain 모듈은 strategy-engine(KisYamlDslParser·JvmStrategyEvaluator)에 의존할 수 없다.
 * - 따라서 domain은 이 함수형 포트만 보유하고, api 모듈이 DSL 파서·평가기를 묶은 어댑터를 주입한다.
 *
 * ### 계약
 * - [evaluateEntry]는 `candles[0..index]` 슬라이스에서 `entryDsl`이 진입(BUY) 시그널을 내면 true.
 * - DSL 파싱·평가 실패는 구현체가 false(미진입)로 흡수한다 — 리서치는 안전 실패가 원칙.
 * - 같은 DSL을 같은 index에 반복 평가하므로 구현체는 AST 캐시 권장(부하 가드는 어댑터 책임).
 */
fun interface CandidateEntryEvaluator {
    /**
     * @param entryDsl 후보의 진입 DSL 식 (예: `close crosses_above ma(10) and close >= open`)
     * @param candles 평가 대상 캔들 (시간순 오름차순, 전체 윈도우)
     * @param index 평가 시점 캔들 인덱스 (이 인덱스까지의 슬라이스로 평가)
     * @return 진입 시그널 발생 여부
     */
    fun evaluateEntry(
        entryDsl: String,
        candles: List<ResearchCandle>,
        index: Int,
    ): Boolean
}
