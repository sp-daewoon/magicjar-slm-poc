package kr.spdaewoon.magicjar.api.messaging

/**
 * STOMP destination 중앙 레지스트리 (single source of truth).
 *
 * 목적: STOMP fanout destination(`/topic/...`) 문자열이 11개 messaging 클래스 + 잡 서비스에
 * 인라인 string template으로 산재해 "fanout인지 source event인지" 헷갈림이 컸다.
 * 이 object가 유일한 wire 출처이며, 모든 forwarder/notifier/잡 서비스가 이 멤버를 참조한다.
 * (frontend `frontend/src/api/stompDestinations.ts` 의 백엔드 대응판 — wire 동일해야 함.)
 *
 * ### Phase 2 — 새 명명규칙 wire 플립 (2026-06-15)
 * Phase 1(레지스트리 중앙화) 완료 후, 빌더 반환값·상수를 새 명명규칙으로 교체했다.
 * 모든 참조부가 이 멤버를 거치므로 값 교체만으로 전체 destination이 따라온다 (clean cut).
 * frontend `stompDestinations.ts` 와 wire 100% 일치 의무.
 *
 * 명명규칙: `/topic/stomp.{owner}.{domain}.{stream}[.{key}]`
 *   - `stomp.api` prefix로 "API-side WebSocket fanout"임을 명시 (Kafka source와 구분).
 *   - 동적 키({code}/{userId}/{requestId}/{interval}/{jobId})는 끝에 둔다.
 *   - 출처: `docs/architecture/backup/realtime-topic-inventory.md`
 *     "STOMP Inventory & Rename Proposal" 표 (Proposed destination 컬럼)
 *
 * ### 설계
 * - 동적 키는 함수 인자, 정적은 상수.
 * - broker prefix(`/topic`)는 [PREFIX] 상수로 단일화.
 *
 * 순수 Kotlin object — Spring/JPA import 0.
 */
object StompDestinations {
    /** STOMP simple broker prefix ([config.WebSocketConfig] `enableSimpleBroker("/topic")` 와 정합). */
    const val PREFIX = "/topic"

    // ──────────────────────────────────────────────────────────────
    // Market — 시세·캔들·지수·섹터·선호도
    // ──────────────────────────────────────────────────────────────

    /** 현재가 tick — Kafka [KafkaTopics.Market.TICK] fanout (MarketTickStompForwarder). */
    fun marketTick(code: String): String = "$PREFIX/stomp.api.market.tick.$code"

    /** 통합 호가 — Kafka [KafkaTopics.Market.ORDERBOOK] fanout (MarketOrderbookStompForwarder). */
    fun marketOrderbook(code: String): String = "$PREFIX/stomp.api.market.orderbook.$code"

    /** 1분봉 closed — Kafka [KafkaTopics.Candle.MINUTE_CLOSED] fanout (MarketCandleStompForwarder). */
    fun marketCandleMinute(code: String): String = "$PREFIX/stomp.api.market.candle.1m.$code"

    /** boundary closed 캔들 (interval = 3m·5m·15m·30m·90m·1h·4h·12h — CandleClosedStompListener). */
    fun marketCandle(
        interval: String,
        code: String,
    ): String = "$PREFIX/stomp.api.market.candle.$interval.$code"

    /** 지수 트렌드 — market trend notifier (StompMarketNotifier, 1분 cron). */
    fun marketIndex(code: String): String = "$PREFIX/stomp.api.market.index.$code"

    /** 섹터 랭킹 — market trend notifier (StompMarketNotifier, 5분 캐시) — 정적. */
    const val MARKET_SECTOR_RANK = "$PREFIX/stomp.api.market.sector.rank"

    /** 트렌드 선호도 sync — 다중 탭 동기화 (StompMarketNotifier). */
    fun marketUserPref(userId: Any): String = "$PREFIX/stomp.api.market.preference.user.$userId"

    // ──────────────────────────────────────────────────────────────
    // Trading / Risk / Account — user-scoped
    // ──────────────────────────────────────────────────────────────

    /** 주문 이벤트 — Kafka `order.*` fanout (StompOrderNotifier). */
    fun orderUser(userId: Any): String = "$PREFIX/stomp.api.order.user.$userId"

    /** 시그널 — Kafka [KafkaTopics.Risk.SIGNAL_GENERATED] fanout (StompSignalDispatcher). */
    fun signalUser(userId: Any): String = "$PREFIX/stomp.api.signal.user.$userId"

    /** 리스크 위반 — Kafka [KafkaTopics.Risk.VIOLATION] fanout (StompRiskNotifier). */
    fun riskUser(userId: Any): String = "$PREFIX/stomp.api.risk.user.$userId"

    /** 계좌 잔고 변경 — Kafka [KafkaTopics.Order.ACCOUNT_BALANCE_CHANGED] fanout (StompAccountNotifier). */
    fun accountBalanceUser(userId: Any): String = "$PREFIX/stomp.api.account.balance.user.$userId"

    /** 정합/킬스위치 — Kafka reconcile/kill switch fanout (StompReconcileNotifier). */
    fun reconUser(userId: Any): String = "$PREFIX/stomp.api.reconcile.user.$userId"

    // ──────────────────────────────────────────────────────────────
    // System — user-scoped + broadcast
    // ──────────────────────────────────────────────────────────────

    /** 시스템 이벤트 (사용자별) — Kafka [KafkaTopics.System.EVENT] fanout (SystemEventStompNotifier). */
    fun systemEventUser(userId: Any): String = "$SYSTEM_EVENT_USER_PREFIX$userId"

    /** [systemEventUser] 의 정적 prefix — `${SYSTEM_EVENT_USER_PREFIX}{userId}` 조합. */
    const val SYSTEM_EVENT_USER_PREFIX = "$PREFIX/stomp.api.system.event.user."

    /** 시스템 이벤트 (broadcast) — 정적 (SystemEventStompNotifier). */
    const val SYSTEM_EVENT_BROADCAST = "$PREFIX/stomp.api.system.event.broadcast"

    // ──────────────────────────────────────────────────────────────
    // AI — request-scoped
    // ──────────────────────────────────────────────────────────────

    /** AI 코멘트 응답 — Kafka [KafkaTopics.Ai.COMMENT_RESPONDED] fanout (AiCommentResponseStompForwarder). */
    fun aiExplain(requestId: String): String = "$PREFIX/stomp.api.ai.explain.$requestId"

    // ──────────────────────────────────────────────────────────────
    // Job — candle recollect 진행률
    // ──────────────────────────────────────────────────────────────

    /** 캔들 재수집 잡 진행률 — CandleRecollectJobService. */
    fun candleRecollectJob(
        code: String,
        jobId: Any,
    ): String = "$PREFIX/stomp.api.job.candle-recollect.$code.$jobId"
}
