package kr.spdaewoon.magicjar.domain.reconcile.port

import kr.spdaewoon.magicjar.domain.reconcile.ReconcileHolding

/**
 * Reconcile 전용 KIS 잔고 조회 포트 (ADR 0041·0070 §2-2 + §8 raw inline KDoc 의무).
 *
 * 구현: consumer 모듈 `KisReconcileBalanceAdapter` — KIS REST `/uapi/domestic-stock/v1/trading/inquire-balance`
 * 페이징 흡수 (CTX_AREA_FK100·NK100 + tr_cont "M"/"F" 룰).
 *
 * 기존 `domain.position.port.KisBalancePort`(ADR 0042 Bootstrap baseline)와 별도 분리한 사유:
 * - reconcile은 단일 호출 페이지 1건이 아닌 **전체 페이징 흡수** 의무 (보유 100주 → 2 페이지 실전)
 * - reconcile 전용 ReconcileHolding 타입 (BigDecimal precision 보존)
 *
 * raw 정합 (헌법 §3 + ADR 0070 §8 inline KDoc 4건 LOCK):
 * - TTTC8434R (실전) / VTTC8434R (모의):
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 *   L18·L97~100·L102~112·L125~134
 * - CTRP6548R (보조):
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_account_balance/inquire_account_balance.py
 *   L14·L16·L42·L56~60
 * - H0STCNI0/H0STCNI9 (cross-ref T-O baseline ADR 0068 §14-6)
 *
 * Spring·JPA 의존 0 — 순수 도메인 인터페이스.
 */
interface ReconcileKisBalancePort {
    /**
     * 사용자 전체 보유 종목 흡수 (페이징 loop 내부 처리).
     *
     * 실전 50건/요청 · 모의 20건/요청 한도 (raw L125~134 정합) →
     * 100주 보유 시 실전 2 페이지 · 모의 5 페이지.
     *
     * KisRateLimiter.acquire 의무 통과 + EGW00201 backoff 1초 + 1회 재시도.
     *
     * @throws Exception KIS API 실패 시 (caller가 reconcile_run.status=ERROR 처리)
     */
    fun fetchAllBalances(userId: String): List<ReconcileHolding>
}
