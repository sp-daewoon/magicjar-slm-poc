package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WS 40 stream slot 예산 결정 (ADR 0123 — ADR 0048 amend, 41→40 정정).
 * Pure function — Spring 의존 0.
 *
 * 슬롯 구성 (한도 단위 = "코드 × tr_id" 채널 — ADR 0123 §1-3, ADR 0124 단일 체결가):
 *   1  = H0STCNI0 (체결통보 — ADR 0042 STAGE 0-4 reserve, T-O 구현)
 *   30 = H0UNCNT0 (KRX+NXT 통합 체결가 — 종목당 1 slot, 관심종목 WS 구독)
 *   10 = H0UNASP0 (통합 호가 — DETAIL/STRATEGY 수요)
 *   WsSlotBudgetLedger/owner priority가 실제 점유를 40 이하로 관리한다.
 *
 * ★ ADR 0123 §1-3 — TOTAL_SLOTS 41→40 KIS-first 정정 (헌법 §3, 자체 추론 0):
 *   KIS 공식 예제 `kis_auth.py` `__runner()`가 40을 hard enforce:
 *     `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")`
 *   KIS 포털이 명시 한도를 공개하지 않아 UNCERTAIN이나, 예제 코드 enforce 값(40)을 보수적 운영 상한으로 채택.
 *   상수가 아닌 ENV 설정값(`MAGICJAR_KIS_WS_MAX_SLOTS`, default 40)으로 운영하는 것은 WsSlotBudgetLedger 책임.
 *   KIS-first raw 출처:
 *     https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
 */
object SubscriptionPolicy {
    /**
     * KIS WS 동시 구독 한도 (ADR 0123 §1-3 — 41→40 KIS-first 정정).
     *
     * 한도 단위 = "코드 × tr_id" 채널 (kis_auth.py open_map key = (tr_id, tr_key) 조합).
     * 운영 환경에서는 WsSlotBudgetLedger가 `MAGICJAR_KIS_WS_MAX_SLOTS` ENV(default 40)로 덮어쓴다 —
     * 본 상수는 ENV 미설정 시 default + 단위 산식 helper용.
     * KIS-first raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     */
    const val TOTAL_SLOTS = 40
    const val EXECUTION_NOTICE_RESERVED = 1 // H0STCNI0
    const val PRICE_TICK_MAX = 30 // H0UNCNT0 통합 체결가 (종목당 1 slot)
    const val ORDERBOOK_MAX = 10 // H0UNASP0

    /**
     * H0STOUP0 시간외 단일가 최대 구독 종목 수 (ADR 0076 Phase 3 B-2).
     *
     * 활성 시간: 16:00~18:00 KST (시간외 단일가 세션).
     * 이 시간대에 H0UNCNT0 체결 tick 0건 → slot 미소모 상태.
     * 따라서 H0STOUP0 20 slot 추가해도 실질 동시 사용 slot:
     *   시간외 단일가(16:00~18:00): H0STOUP0(20) + H0STCNI0(1) = 21 ≤ 40 안전.
     *
     * KIS-first 출처: open-trading-api domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   tr_id = "H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     */
    const val OVERTIME_SINGLE_PRICE_MAX = 20 // H0STOUP0 (16:00~18:00 KST 한정)

    fun priceTickSlotsAvailable(currentSubscribed: Int): Int = (PRICE_TICK_MAX - currentSubscribed).coerceAtLeast(0)

    /**
     * 단일 회계 산식 helper (ADR 0123 §3-1) — 가용 채널 slot.
     *
     * `available = maxSlots - reserved(체결통보 1) - occupiedChannels`.
     * "코드 × tr_id" 채널 단위. WsSlotBudgetLedger가 maxSlots를 ENV(`MAGICJAR_KIS_WS_MAX_SLOTS`)로 주입.
     * 음수 방지(coerceAtLeast 0) — 한도 초과 상태에서도 available=0 반환 (선제 차단·eviction 판단은 R2).
     */
    fun channelSlotsAvailable(
        occupiedChannels: Int,
        maxSlots: Int = TOTAL_SLOTS,
        reserved: Int = EXECUTION_NOTICE_RESERVED,
    ): Int = (maxSlots - reserved - occupiedChannels).coerceAtLeast(0)
}
