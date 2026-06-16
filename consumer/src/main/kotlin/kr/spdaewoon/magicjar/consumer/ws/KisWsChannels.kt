package kr.spdaewoon.magicjar.consumer.ws

/**
 * KIS WebSocket `tr_id` 채널 중앙 문서/별칭 레지스트리 (documentation only).
 *
 * ### 목적
 * KIS WebSocket `tr_id`(`H0UNCNT0` 등)는 외부 공식 식별자라 의미가 암호처럼 보여
 * 로그·메트릭·slot ledger·운영 UI에서 맥락이 부족하다. 이 object는 7개 `tr_id`의
 * **의미 + 운영 맥락 + 내부 표시용 별칭(nextName)**을 한곳에 모은다.
 *
 * ### 절대 불변식 (KIS-first raw fetch 의무 · 헌법 §3)
 * - **outbound `tr_id` wire 값은 절대 변경 금지.** 이 object의 [TrId] 상수 값은
 *   KIS 공식 spec 그대로이며, 구독/해제 로직이 사용하는 기존
 *   `KisWebSocketBridge.TR_ID_UNIFIED_*` 등과 **동일 wire 값**이다.
 * - 이 object는 **문서화/별칭 전용**이다. 구독 경로(buildSubscribeMessage)는 기존 상수를
 *   계속 사용하며 behavior change 0. 별칭([Alias])은 로그·메트릭·slot ledger 라벨 등
 *   **내부 표시 용도로만** 점진 도입한다 (지금은 주석/상수 모음, 실제 치환은 별도 PR).
 *
 * ### raw 출처 (KIS-first)
 * - 통합 체결가/호가/시장상태: 한국투자증권 open-trading-api 실시간 시세 명세
 *   - `H0UNCNT0` 국내주식 실시간체결가(통합 KRX+NXT) — KisWsMessageParser `ccnl_total()` 정합
 *   - `H0UNASP0` 국내주식 실시간호가(통합) — KisWsMessageParser orderbook 정합
 *   - `H0UNMKO0` 국내주식 실시간 장운영/VI(통합) — KisWsMarketStatusParser 정합
 *   - `H0STOUP0` 국내주식 시간외 실시간체결가(KRX) [실시간-042]
 *   - `H0STCNI0` 실전 체결통보, `H0STCNI9` 모의 체결통보
 *   - `H0STCNT0` legacy/base KRX 체결가 파서 참조 (parser/comment 호환 — ADR 0124로 발사 폐기, 파싱 참조만 잔류)
 * - 별칭(nextName) 규칙: `docs/architecture/backup/realtime-topic-inventory.md`
 *   "KIS WebSocket Inventory & Alias Proposal" 표 — `kisws.consumer.{domain}.{stream}.{variant}.{trId}`
 *
 * ### ADR 정합
 * - ADR 0124 — 체결가는 `H0UNCNT0` 단일 채널(KRX+NXT 통합·종목당 1채널·`NEW_MKOP_CLS_CODE` 세션 구분).
 *   기존 `H0STCNT0`+`H0NXCNT0` 이중 발사 폐기. `H0STCNT0`은 파싱 참조 호환용으로만 잔류.
 * - ADR 0123 — 호가 `H0UNASP0` slot ledger 점유.
 */
object KisWsChannels {
    /** KIS 공식 outbound `tr_id` wire 값 (변경 절대 금지). */
    object TrId {
        /** 국내주식 실시간체결가 (통합 KRX+NXT) — active subscription. */
        const val UNIFIED_TICK = "H0UNCNT0"

        /** 국내주식 실시간호가 (통합) — active subscription. */
        const val UNIFIED_ORDERBOOK = "H0UNASP0"

        /** 국내주식 실시간 장운영/VI (통합) — active subscription. */
        const val UNIFIED_MARKET_STATUS = "H0UNMKO0"

        /** 국내주식 시간외 실시간체결가 (KRX) [실시간-042] — 시간외 단일가 세션 한정. */
        const val OVERTIME_TICK = "H0STOUP0"

        /** 실전 체결통보 — REAL 모드 active. */
        const val EXECUTION_NOTICE_REAL = "H0STCNI0"

        /** 모의 체결통보 — MOCK 모드 active. */
        const val EXECUTION_NOTICE_MOCK = "H0STCNI9"

        /** legacy/base KRX 체결가 — 파서/주석 호환 참조만 (ADR 0124로 발사 폐기). */
        const val KRX_TICK_LEGACY = "H0STCNT0"
    }

    /**
     * 내부 표시용 별칭(nextName) — 로그·메트릭·slot ledger·운영 UI 라벨 후보.
     * 출처: realtime-topic-inventory.md "KIS WebSocket Inventory & Alias Proposal".
     * (현재는 상수 모음만 — 실제 라벨 치환은 별도 마이그레이션 PR)
     */
    object Alias {
        // tr_id: H0UNCNT0
        const val UNIFIED_TICK = "kisws.consumer.market.tick.unified.H0UNCNT0"

        // tr_id: H0UNASP0
        const val UNIFIED_ORDERBOOK = "kisws.consumer.market.orderbook.unified.H0UNASP0"

        // tr_id: H0UNMKO0
        const val UNIFIED_MARKET_STATUS = "kisws.consumer.market.status-vi.unified.H0UNMKO0"

        // tr_id: H0STOUP0
        const val OVERTIME_TICK = "kisws.consumer.market.tick.overtime.H0STOUP0"

        // tr_id: H0STCNI0
        const val EXECUTION_NOTICE_REAL = "kisws.consumer.order.execution.real.H0STCNI0"

        // tr_id: H0STCNI9
        const val EXECUTION_NOTICE_MOCK = "kisws.consumer.order.execution.mock.H0STCNI9"

        // tr_id: H0STCNT0
        const val KRX_TICK_LEGACY = "kisws.consumer.market.tick.krx-legacy.H0STCNT0"
    }
}
