package kr.spdaewoon.magicjar.consumer.config

import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * Kafka topic 자동 생성 설정 (dispatch §3-2-G 정합).
 *
 * 생성 topic (권위 wire는 KafkaTopics 레지스트리):
 * - kafka.consumer.evt.market.tick.v1            — KIS WS tick + polling fallback publish (retention 1h)
 * - kafka.consumer.evt.market.candle.1m.closed.v1 — 1m closing 캔들 (retention 1d)
 * - kafka.consumer.evt.market.orderbook.v1       — H0UNASP0 통합 호가 스냅샷 (retention 1h)
 * - kafka.consumer.evt.market.status.v1          — H0UNMKO0 통합 장운영/VI 이벤트 (retention 1h)
 * - kafka.api.cmd.symbol.detail-realtime.v1      — 종목 상세 실시간 sub/unsub 명령 (retention 1h)
 * - kafka.api.cmd.market.realtime-demand.v1      — 전략 후보 실시간 관찰 수요 (retention 1h)
 * - kafka.shared.evt.system.event.v1             — 운영 이벤트 대시보드 STOMP fanout source (retention 7d)
 *
 * PR-9b market-data 담당. PR-10b 보강.
 */
@Configuration
class KafkaTopicConfig {
    /**
     * 실시간 tick (KIS WS live + polling fallback).
     * retention: 1h (시세 데이터 — 오래 보관 불필요)
     */
    @Bean
    fun marketTickTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.TICK)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 1분봉 closing 캔들 (TickToMinuteAggregator publish).
     * retention: 1d (일봉 집계 충분 시간)
     */
    @Bean
    fun marketCandleMinuteClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.MINUTE_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 호가 스냅샷 (KIS WS H0UNASP0 → KisWebSocketBridge publish).
     * retention: 1h (실시간 호가 — 장기 보관 불필요)
     * PR-10b 신규.
     */
    @Bean
    fun marketOrderbookTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.ORDERBOOK)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 장운영/VI 이벤트 (KIS WS H0UNMKO0 → KisWebSocketBridge publish).
     * retention: 1h (자동청산 watcher 실시간 소비용)
     */
    @Bean
    fun marketStatusTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.STATUS)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 종목 상세 실시간 sub/unsub 명령 (api → consumer RealtimeSubscriptionRouter + OrderbookSubscriptionRouter).
     * retention: 1h (명령 재처리 필요 없음)
     */
    @Bean
    fun symbolDetailCommandTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.System.SYMBOL_DETAIL_COMMAND)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 전략/스크리너 후보 실시간 관찰 수요.
     * watchlist 자동 등록 없이 owner/priority/TTL만 전달한다.
     */
    @Bean
    fun marketRealtimeDemandTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.REALTIME_DEMAND)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 종목 마스터 sync 변경 이벤트 (InstrumentSyncJob → consumers).
     * partition key = market (KOSPI·KOSDAQ 분리).
     * retention: 7d (신규 consumer 재처리 가능).
     * PR-11a T-IM1 market-data 신규.
     */
    @Bean
    fun instrumentUpdatedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Data.INSTRUMENT_UPDATED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0064 A-1·C-1 — boundary 마감 토픽 (v3.3.0 §34)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 3분봉 boundary closing 이벤트.
     * NXT 통합 08:03~20:00 KST 240건/일 (TradingView 정합).
     * api StompCandleClosedForwarder → /topic/market.candle.3m.{code} STOMP fanout.
     * retention: 1d (하루 배치·fallback 충분)
     */
    @Bean
    fun marketCandle3mClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.M3_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 5분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT 통합 08:05~20:00 KST 144건/일 (TradingView 정합).
     * api StompCandleClosedForwarder → /topic/market.candle.5m.{code} STOMP fanout.
     * retention: 1d (하루 배치·fallback 충분)
     */
    @Bean
    fun marketCandle5mClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.M5_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 15분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT 통합 08:15~20:00 KST 48건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle15mClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.M15_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 30분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT 통합 08:30~20:00 KST 24건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle30mClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.M30_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 90분봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT 통합 09:30·11:00·12:30·14:00·15:30·17:00·18:30·20:00 KST 8건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle90mClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.M90_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 1시간봉 boundary closing 이벤트 (ADR 0064 §4-5 C-1 채택).
     * NXT 통합 09:00~20:00 KST 12건/일.
     * retention: 1d
     */
    @Bean
    fun marketCandle1hClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.H1_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 4시간봉 boundary closing 이벤트.
     * NXT 통합 08:00 anchor 기준 12:00·16:00·20:00 KST 마감.
     * retention: 1d
     */
    @Bean
    fun marketCandle4hClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.H4_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    /**
     * 12h(NXT 통합 세션 전체) boundary closing 이벤트.
     * NXT 통합 08:00 anchor 기준 20:00 KST 마감.
     * retention: 1d
     */
    @Bean
    fun marketCandle12hClosedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Candle.H12_CLOSED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0066 §11-1 Phase 1 (6) — T-SIG 시그널 생성 토픽 (W3 두 번째 트랙)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 시그널 발생 이벤트 (ADR 0066 §7-1 SignalKafkaProducer + §9-2 운영 영향).
     *
     * Phase 1 strategy-engine sub-agent publish — Phase 2 backend-core가
     * STOMP `/topic/signal.user.{userId}` fanout consumer 구현.
     *
     * partition key = userId — 사용자별 ordering 보장.
     * retention: 7d (audit · MCP 후속 정합 — ADR 0066 §9-2)
     *
     * 부하 예측 (ADR 0066 §9-2): 1,000 msg/day = 0.012 msg/s — 매우 가벼움.
     *
     * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
     */
    @Bean
    fun signalGeneratedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Risk.SIGNAL_GENERATED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0067 §7-1 — T-RISK 리스크 게이트 토픽 2건 (W3 세 번째 트랙)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Risk 위반 이벤트 (ADR 0067 §7-1 RiskViolationKafkaProducer + F-1 정합).
     *
     * Phase 1 strategy-engine SignalGeneratedKafkaListener BLOCKED 분기에서 publish —
     * ADR 0044 Notification System 진입점 + Phase 2 backend-core STOMP `/topic/risk.user.{userId}` fanout 연계.
     *
     * partition key = userId — 사용자별 ordering 보장.
     * retention: 7d (audit · MCP 후속 정합)
     *
     * 부하 예측 (ADR 0067 §9-2): ~200 msg/day = 0.0023 msg/s — 매우 가벼움.
     *
     * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
     */
    @Bean
    fun riskViolationTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Risk.VIOLATION)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    /**
     * 주문 의도(Order Intent) — Risk Gateway ALLOWED 시그널 publish (ADR 0067 §7-1 + E-1 정합).
     *
     * Phase 1 strategy-engine SignalGeneratedKafkaListener ALLOWED 분기 publish —
     * T-O 트랙 후행 consumer 진입점 (decoupled chain).
     *
     * partition key = userId.
     * retention: 7d (audit · 주문 재처리 보조)
     *
     * 부하 예측 (ADR 0067 §9-2): ~800 msg/day = 0.0093 msg/s — 매우 가벼움.
     *
     * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
     */
    @Bean
    fun orderIntentTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Order.INTENT)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    /**
     * 기업 행위(Corporate Action) 적용 이벤트.
     * CorpActionSyncJob → CandleAdjustmentJob 트리거.
     * partition key = instrumentCode (종목별 순서 보장).
     * retention: 1d (소급 보정 완료 후 재처리 불필요).
     * PR-29b T-CA1 market-data 신규 (dispatch-spec §6-3 KafkaTopicConfig PR-29b 단독 LOCK).
     *
     * KIS ksdinfo 6 endpoint 출처 (ADR 0018 §2-5):
     *   HHKDB669100C0 paidin-capin, HHKDB669101C0 bonus-issue,
     *   HHKDB669102C0 dividend,     HHKDB669104C0 merger-split,
     *   HHKDB669105C0 rev-split,    HHKDB669106C0 cap-dcrs
     */
    @Bean
    fun corpActionAppliedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Data.CORP_ACTION_APPLIED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0074 Phase 3 — KIS 키 변경 이벤트 (kafka.api.evt.system.kis-key.changed.v1)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * KIS 키 변경 이벤트 — api OnboardingService publish → consumer KisWebSocketBridge reconnect.
     * retention: 7d (운영 감사 + 1주 추적 — ADR 0074 §2-2-3 정합)
     * publisher: api OnboardingService (Phase 1 backend-core)
     * consumer: SystemKisKeyChangedListener (Phase 3 consumer — 본 파일)
     *
     * AI 호출 0 — 룰 5 + ADR 0074 §11-2 정합.
     */
    @Bean
    fun systemKisKeyChangedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.System.KIS_KEY_CHANGED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    /**
     * 시스템 운영 이벤트 — persistence-shared SystemEventLoggerAdapter publish →
     * api SystemEventStompNotifier fanout.
     *
     * partition key = userId 또는 broadcast.
     * retention: 7d (운영 대시보드 즉시성 + Kafka UI 추적용; DB system_event 가 장기 source-of-truth)
     */
    @Bean
    fun systemEventTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.System.EVENT)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0075 T-NEWS Phase 1 — DART 공시 신규 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * DART 공시 신규 이벤트 (ADR 0075 §3-1 LOCK — T-NEWS Phase 1 market-data).
     *
     * publisher : api DartDisclosurePollingScheduler (1분 cron) + DisclosureKafkaProducer
     * consumer  : Phase 4 잔류 (T-NOTIFY Slack dispatcher + 사용자 트리거 시)
     *
     * partition key = corp_code (기업별 — rcept_no 멱등성으로 ordering 중요도 낮음).
     * retention: 7d (운영 추적 + 정정공시 P0 알림 ADR 0044 reuse 준비)
     *
     * 이벤트 스키마 v1 (ADR 0075 §3-3 LOCK):
     *   eventId, rceptNo, corpCode, stockCode, rceptDt, corpName, reportNm,
     *   isCorrection, url, occurredAt, schemaVersion=1
     *
     * // AI 호출 0 건 (ADR 0075 §11-2 정합)
     */
    @Bean
    fun disclosureNewTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Data.DISCLOSURE_NEW)
            .partitions(3)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0075 T-NEWS Phase 2 — Naver 뉴스 수집 이벤트
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Naver 뉴스 수집 이벤트 (ADR 0075 §3-1 LOCK — T-NEWS Phase 2 backend-core).
     *
     * publisher : api WatchlistNewsScheduler (1분 cron) + NewsKafkaProducer
     * consumer  : Phase 5 잔류 (T-AI prompt input reuse · 1차는 publish only)
     *
     * partition key = stock_code (종목별 — 최신순 ordering 가능성 고려).
     * retention: 7d (T-AI prompt input 재처리 + 사용자 검색 추적 — ADR 0075 §3-1)
     *
     * 이벤트 스키마 v1 (ADR 0075 §3-3 LOCK):
     *   eventId, source, stockCode, title, bodySummary, url, urlHash,
     *   publishedAt, occurredAt, schemaVersion=1
     *
     * // AI 호출 0 건 (ADR 0075 §11-2 정합)
     */
    @Bean
    fun newsCollectedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Data.NEWS_COLLECTED)
            .partitions(3)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0100 T-MARKET Phase 1 — 지수·섹터 1분 cron 갱신 이벤트 2건
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 시장 지수 갱신 이벤트 (ADR 0100 §2-6 STOMP fanout source LOCK — T-MARKET Phase 1).
     *
     * publisher : batch MarketIndexSyncJob (1분 cron · 26 row × 1min)
     * consumer  : R2 backend-core MarketIndexUpdatedListener → STOMP /topic/market.index.{code}
     *
     * partition key = code (지수·섹터 코드 — 종목별 ordering).
     * retention: 1h (1분 cron 본질 + STOMP fanout 즉시 소비 — 장기 보관 불필요).
     *
     * 이벤트 스키마 v1 (ADR 0100 §2-6 정합):
     *   code, category, ts, price, changeRate, volume, occurredAt, schemaVersion=1
     *
     * 호출 빈도 (ADR 0100 §2-4): 26 msg/min × 6h 장중 = ~9360 msg/day = 0.108 msg/s — 매우 가벼움.
     *
     * // AI 호출 0 건 (룰 5 + ADR 0100 §1-3 자본 path 영향 0)
     */
    @Bean
    fun marketIndexUpdatedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.INDEX_UPDATED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "3600000") // 1h
            .build()

    /**
     * 섹터 ranking 갱신 이벤트 (ADR 0100 §2-6 STOMP fanout source LOCK — T-MARKET Phase 1).
     *
     * publisher : batch MarketIndexDailyCloseJob (16:30 KST + 사용자 변경 시 lazy) + 1분 cron 옵션
     * consumer  : R2 backend-core MarketSectorUpdatedListener → STOMP /topic/market.sector.rank
     *
     * partition key = "ALL" (단일 ranking — broadcast).
     * retention: 1d (장 마감 ranking 일 1회 본질).
     *
     * 이벤트 스키마 v1 (ADR 0100 §2-6 정합):
     *   ts, sectors[]={sectorCode, sectorName, price, changeRate, tradeValue},
     *   occurredAt, schemaVersion=1
     *
     * // AI 호출 0 건 (룰 5 + ADR 0100 §1-3 자본 path 영향 0)
     */
    @Bean
    fun marketSectorUpdatedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Market.SECTOR_UPDATED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "86400000") // 1d
            .build()

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0026 amend T-AI Phase 1 (W4 R1) — AI 코멘트 요청·응답 토픽 2건
    //   사용자 명시 클릭 trigger 한정 — 자동 호출 0건 (매매 결정권 0)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * AI 코멘트 요청 (ADR 0026 §2-4 amend — 사용자 클릭 → POST /api/ai/explain → Kafka publish).
     *
     * publisher : api AiExplainController (사용자 트리거 only)
     * consumer  : consumer AiCommentDispatcherListener (W4 R1 신규)
     *
     * partition key = userId — 같은 사용자 요청 ordering 보장 (1일 한도 정확성).
     * retention: 7d (audit · MCP 후속 정합)
     *
     * 부하 예측 (ADR 0026 §2-2 amend — 5 진입점 advisory only):
     *   사용자 평균 10 클릭/일 × 100 사용자 = 1,000 msg/day = 0.012 msg/s — 매우 가벼움.
     *
     * 이벤트 스키마 v1:
     *   requestId · userId · type (SYMBOL/CHART/STRATEGY/SIGNAL/MARKET) ·
     *   contextJson · occurredAt · schemaVersion=1
     */
    @Bean
    fun aiCommentRequestedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Ai.COMMENT_REQUESTED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()

    /**
     * AI 코멘트 응답 (ADR 0026 §2-4 amend — adapter 호출 결과 → Kafka publish → api STOMP fanout).
     *
     * publisher : consumer AiCommentDispatcherListener
     * consumer  : api AiExplainStompPublisher (STOMP /topic/ai.explain.{requestId} fanout)
     *
     * partition key = userId — 같은 사용자 응답 ordering 보장.
     * retention: 7d
     *
     * 이벤트 스키마 v1:
     *   requestId · userId · provider · model · content · tokensIn · tokensOut ·
     *   latencyMs · cached · graceful · occurredAt · schemaVersion=1
     */
    @Bean
    fun aiCommentRespondedTopic(): NewTopic =
        TopicBuilder
            .name(KafkaTopics.Ai.COMMENT_RESPONDED)
            .partitions(1)
            .replicas(1)
            .config("retention.ms", "604800000") // 7d
            .build()
}
