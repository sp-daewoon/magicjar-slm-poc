package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WebSocket 구독 포트 (KIS 40건 구독 한도).
 * 구현은 consumer 모듈 ws 어댑터에서 (PR-8).
 *
 * 슬롯 예산:
 * - H0UNCNT0 통합 체결가: 종목당 1 slot (subscribe/unsubscribe)
 * - H0UNASP0 통합 호가: 종목당 1 slot (subscribeOrderbook/unsubscribeOrderbook)
 * - H0UNMKO0 통합 장운영/VI: VI 정책 EXIT_MARKET/ALERT_ONLY 보유 종목당 1 slot
 */
interface KisWsPort {
    /**
     * WebSocket 연결 시작 + approval_key 발급 + 기존 구독 종목 재구독.
     *
     * WatchlistIngestionWorker 또는 RealtimeSubscriptionRouter가 실시간 수요 발견 후 호출.
     * 이미 연결된 상태이면 no-op (idempotent).
     *
     * KIS-first 출처:
     * - approval_key endpoint: /oauth2/Approval (KisApprovalAdapter)
     * - WebSocket URL: wss://ops.koreainvestment.com:21000 (prod) / :31000 (mock/VTS)
     *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/
     *
     * ADR 0028 §2-1 + 2026-05-31 owner split: watchlist/detail/strategy 수요가 연결을 요청할 수 있다.
     * ADR 0048 §2-4: 연결 실패 시 지수 백오프 재시도.
     */
    fun connect()

    fun subscribe(codes: List<String>)

    fun unsubscribe(codes: List<String>)

    fun subscribedCodes(): Set<String>

    /**
     * KIS WS 통합 호가 구독 (H0UNASP0).
     */
    fun subscribeOrderbook(codes: List<String>)

    /**
     * KIS WS 통합 호가 구독 해제 (H0UNASP0).
     */
    fun unsubscribeOrderbook(codes: List<String>)

    /**
     * 현재 호가 구독 중인 종목코드 Set.
     */
    fun subscribedOrderbookCodes(): Set<String>

    /**
     * KIS WS 통합 장운영정보/VI 구독 (H0UNMKO0).
     */
    fun subscribeMarketStatus(codes: List<String>)

    /**
     * KIS WS 통합 장운영정보/VI 구독 해제 (H0UNMKO0).
     */
    fun unsubscribeMarketStatus(codes: List<String>)

    /**
     * 현재 장운영정보/VI 구독 중인 종목코드 Set.
     */
    fun subscribedMarketStatusCodes(): Set<String>
}
