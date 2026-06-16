package kr.spdaewoon.magicjar.domain.screener.port

import kr.spdaewoon.magicjar.domain.screener.ScreenerMatch

/**
 * Screener 랭킹 port (ADR 0071 §2-5 E-1 + spec §2-2).
 *
 * multi-criteria 4 기준 fixed weights:
 * - signal 0.40 + volume 0.30 + atr 0.20 + cap 0.10 = 1.00 LOCK
 *
 * Phase 1: consumer 모듈 `MultiCriteriaRanker` 구현체 LOCK.
 *
 * ### 헌법 §6 정합
 * abstract fun 단독 — body 가진 default fun 0건 LOCK.
 *
 * ### AI 호출 0 LOCK (ADR 0071 §2-9 + 개발 룰 5)
 * 본 port + 구현체는 LLM provider import 0 + LLM 가중치 호출 0 — pure math weighted sum.
 *
 * ### post-MVP backlog (ADR 0071 §11-1)
 * 사용자 custom 가중치는 backlog §54 (DSL meta 또는 env override) — 본 사이클 fixed weights 단독.
 */
interface ScreenerRankerPort {
    /**
     * 매칭 종목 리스트를 multi-criteria 점수로 랭킹.
     *
     * 입력: ranker 호출 전 [ScreenerMatch.rank]=0 + [ScreenerMatch.score] 임시값.
     * 출력: rank 1-base 부여 + score breakdown.total 정합 + descending sort.
     *
     * 동점 처리: 1차 LOCK은 instrumentCode 사전순 stable sort (재현 결정성).
     *
     * @param matches 평가 완료 매칭 list (BUY 시그널 종목)
     * @param limit 최대 결과 수 (1-base 랭킹 limit 적용)
     * @return ranked + sorted (descending score) — list size ≤ limit
     */
    fun rank(
        matches: List<ScreenerMatch>,
        limit: Int,
    ): List<ScreenerMatch>
}
