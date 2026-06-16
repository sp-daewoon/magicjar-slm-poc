package kr.spdaewoon.magicjar.consumer.ws

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisApprovalAdapter
import kr.spdaewoon.magicjar.consumer.market.LiveCandleUpdater
import kr.spdaewoon.magicjar.consumer.order.KisExecutionWsListener
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleSession
import kr.spdaewoon.magicjar.domain.market.candle.TodayCandleSeedWindow
import kr.spdaewoon.magicjar.domain.market.port.KisRestPriceMetricsPort
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.QuoteCachePort
import kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort
import kr.spdaewoon.magicjar.domain.market.tick.TickEvent
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import reactor.util.concurrent.Queues
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket 브릿지 — domain port KisWsPort 구현 (ADR 0048 §3-1).
 *
 * KIS-first 출처:
 * - WebSocket URL: open-trading-api examples_user/kis_auth.py my_url_ws 설정
 *   실전: wss://ops.koreainvestment.com:21000
 *   모의: wss://ops.koreainvestment.com:31000 (VTS)
 * - approval_key: KisApprovalAdapter.getApprovalKey() (/oauth2/Approval endpoint)
 * - subscribe 메시지: {"header": {"approval_key": ..., "tr_type": "1", "custtype": "P"}, "body": {"input": {"tr_id": "H0UNCNT0", "tr_key": "005930"}}}
 * - unsubscribe: tr_type "2"
 * - PINGPONG heartbeat: "PINGPONG" 수신 시 동일 텍스트 송신
 *
 * H0UNCNT0 통합 체결가 구독 (ADR 0124 — 단일 채널):
 * - subscribe(codes) 시 종목당 H0UNCNT0 1채널(KRX+NXT 통합)로 구독한다. NEW_MKOP_CLS_CODE로 세션 구분.
 * - KIS-first 출처: open-trading-api examples_llm/domestic_stock/ccnl_total/ccnl_total.py
 *   tr_id = "H0UNCNT0" — 국내주식 실시간체결가 (통합)
 *
 * H0STOUP0 시간외 단일가 체결가 구독 (ADR 0076 Phase 3 B-2 — 16:00~18:00 KST):
 * - subscribeOvertimeSinglePrice(codes) / unsubscribeOvertimeSinglePrice(codes)
 * - 16:00~18:00 KST 한정 활성 — OvertimeSinglePriceSubscriber가 Scheduled로 관리.
 * - KIS-first 출처: open-trading-api examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
 *   tr_id = "H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
 *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
 *
 * 40 slot 예산 (ADR 0123 KIS-first 정정 + ADR 0124 H0UNCNT0 단일 채널):
 * - H0STCNI0 체결통보: 1 slot (reserve — 실제 subscribe는 T-O)
 * - H0UNCNT0 체결가: 종목당 1 slot (KRX+NXT 통합 — 이중 발사 폐기)
 * - H0UNASP0 통합 호가: DETAIL/STRATEGY demand만 점유(종목당 1 slot, KRX+NXT 통합)
 * - H0UNMKO0 통합 장운영/VI: VI 정책 EXIT_MARKET/ALERT_ONLY 보유 종목만 점유(종목당 1 slot)
 * - WsSlotBudgetLedger가 채널 점유를 단일 회계한다.
 *   시간외 단일가(16:00~18:00): H0STOUP0(≤20) + H0STCNI0(1) ≤ 40 안전.
 *
 * 재연결 지수 백오프 (ADR 0048 §2-4-B):
 * 1차: 즉시, 2차: 2s, 3차: 5s, 4차: 10s, 5차+: 30s
 *
 * heartbeat 무신호 감지 → reconnect 트리거 (ADR 0048 §2-4-A + REAL canary F3):
 *   - `magicjar.kis.ws.heartbeat-timeout-seconds` 설정값 사용 (기본 10s).
 *   - ADR 0076 D-1의 30s/60s 동적 timeout은 장마감·시간외 자연 무신호 재연결 스팸 완화 목적이었으나,
 *     REAL canary에서 ghost session 2h 공백이 확인되어 운영 설정으로 더 엄격하게 조절한다.
 *
 * @PreDestroy graceful shutdown ≤ 5s (ADR 0045).
 */
@Component
class KisWebSocketBridge(
    private val approvalAdapter: KisApprovalAdapter,
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val liveCandleUpdater: LiveCandleUpdater,
    private val quoteCachePort: QuoteCachePort,
    /** KIS WS 전용 WebSocketClient — SslProvider.JDK + TLSv1.2/1.3 명시 (WebClientConfig 빈).
     *  SSL handshake timeout fix (PR #278 DART 패턴 동일 적용).
     *  출처: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/ */
    @Qualifier("kisWebSocketClient")
    private val wsClient: ReactorNettyWebSocketClient,
    @Value("\${magicjar.kis.ws.url-prod:wss://ops.koreainvestment.com:21000}")
    private val wsUrlProd: String,
    @Value("\${magicjar.kis.ws.url-vps:wss://ops.koreainvestment.com:31000}")
    private val wsUrlVps: String,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String,
    @Value("\${magicjar.kis.ws.user-id:}")
    private val defaultUserId: String,
    @Value("\${magicjar.kis.ws.heartbeat-timeout-seconds:10}")
    private val heartbeatTimeoutSeconds: Long = 10L,
    @Value("\${magicjar.kis.ws.polling-fallback-timeout-seconds:30}")
    private val pollingFallbackTimeoutSeconds: Long = 30L,
    /**
     * 종목별 무tick watchdog 임계(초) — SUBSCRIBE SUCCESS 후 이 시간 동안 H0UNCNT0 체결 tick이
     * 0건이면 WARN(silent 누락 차단 §11-2). 0 이하면 watchdog 비활성(롤백 toggle).
     *
     * ### 사유 (2026-06-15 REAL canary 결함 ② — 403870 H0UNCNT0 silent 누락)
     * ACTIVE 전략 구독 7종목 중 403870만 실시간 tick 0(나머지 6종목 정상). 구독은 SUBSCRIBE SUCCESS를
     * 받았으나 H0UNCNT0 체결 프레임이 0건이고, ADR 0091/0124의 빈응답 WARN 가드(=프레임이 *오긴 왔는데*
     * 비어있는 경우)도 발화 안 함 — KIS가 해당 종목에 대해 프레임 자체를 0건 보내는 경우는 기존 가드 사각.
     * 본 watchdog는 "구독 SUCCESS 후 N초간 프레임 0"을 정규장 세션 중에 주기 점검해 관측 가능하게 한다.
     *
     * default 120s — 활발히 거래되는 종목은 보통 수 초 내 첫 tick. 저유동 종목 오탐 완화 위해 보수적으로 길게.
     */
    @Value("\${magicjar.kis.ws.no-tick-watchdog-seconds:120}")
    private val noTickWatchdogSeconds: Long = 120L,
    /**
     * KIS WS 체결통보(H0STCNI0/H0STCNI9) 수신 시 위임 listener (ADR 0068 §14-6 LOCK).
     *
     * ObjectProvider 사용 사유 (graceful 합류):
     * - KisExecutionWsListener는 T-O Phase 1 잔여 컴포넌트 — 컨테이너 빈 부재 환경(qa fixture 등)에서도 KisWebSocketBridge 부팅 보장.
     * - 정상 운영 시 always present — null 분기는 fallback 안전망.
     * - 헌법 §6 + ADR 0066 ObjectProvider 패턴 정합 (KisExecutionWsListener 본체와 동일 패턴).
     */
    private val executionListenerProvider: ObjectProvider<KisExecutionWsListener>,
    /**
     * SystemEvent emit port (P1 spec §6.1 emit point #4·#5 — WS_DISCONNECTED·WS_RECONNECTED).
     *
     * disconnect/reconnect 사건 그룹화: disconnect 시점 UUID 생성 → reconnect 시 동일 UUID 재사용
     * (correlationId — UI 표시 시 1쌍 그룹).
     *
     * graceful 정책 (헌법 §6 + spec §6.1): emit 실패가 WS 흐름 cascade 차단 방지 — runCatching wrap 의무.
     */
    private val systemEventLogger: SystemEventLogger,
    /**
     * 당일 1d Hash 시드용 공식 KIS OHLCV 조회 포트 ([TodayCandleSeedPort.seedTodayCandle]).
     *
     * 구현 = KisRestPriceAdapter.fetchPriceMetrics (inquire-daily-itemchartprice FHKST03010100 output1).
     * 2026-05-20 NXT 시간외 contamination 회피 사유로 inquire-price(FHKST01010100) stck_prpr 대신
     * 이 endpoint를 정규장 source로 채택 — candle DB·MTS와 동일 정규장 시가·고저·누적거래량 반환.
     * output1: stck_oprc(시가)·stck_hgpr(고가)·stck_lwpr(저가)·stck_prpr(현재가)·acml_vol(누적거래량).
     *
     * ObjectProvider 사용 사유: KisWsPort 구현은 qa fixture 등 REST 어댑터 부재 환경에서도 부팅 보장
     * (체결통보 listener와 동일 graceful 패턴) — 부재 시 시드 no-op.
     */
    private val priceMetricsPortProvider: ObjectProvider<KisRestPriceMetricsPort>,
    /**
     * 당일 시드 REST 자격증명 userId resolve용 credential repository.
     *
     * `magicjar.kis.ws.user-id`(WS approval 발급 식별자)는 REST OAuth 자격증명(user_kis_credential)의
     * user_id와 다를 수 있다 — 라이브 실측(2026-06-10): approval=thdeodns 캐시 정상이나 REST 토큰은
     * 74683223(키 보유 user)로만 발급되어 시드가 "자격증명 없음"으로 전량 skip. 시드는 REST 경로이므로
     * REAL 키 보유 userId를 우선 resolve하고, 부재 시 ws.user-id fallback.
     * ObjectProvider: qa fixture 등 repo 부재 환경 부팅 보장(시드 no-op graceful 정합).
     */
    private val credentialRepositoryProvider: ObjectProvider<ConsumerUserKisCredentialJpaRepository>,
    @Value("\${magicjar.kis.today-candle-seed.pre-open-guard-enabled:true}")
    private val todayCandleSeedPreOpenGuardEnabled: Boolean = true,
    /**
     * KIS WS 구독 슬롯 단일 회계기 (ADR 0123 R1 — `WsSlotBudgetLedger`).
     *
     * subscribe/unsubscribe 발사 직후 채널(code × trId) 점유 등록/취소, OPSP0008 거부 시 회계 보정.
     * ObjectProvider 사용 사유: qa fixture 등 ledger 빈 부재 환경에서도 KisWebSocketBridge 부팅 보장
     * (체결통보 listener·priceMetricsPort와 동일 graceful 패턴) — 부재 시 회계 no-op.
     * `MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=false` 시 ledger 자체가 no-op (롤백 toggle).
     */
    private val slotLedgerProvider: ObjectProvider<kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger>,
) : KisWsPort,
    TodayCandleSeedPort {
    companion object {
        // backlog §29 #4 — Sinks unicast bounded buffer 사이즈 (reconnect 폭주 hardcap).
        // 정상 시 send queue ~6 KB(50 종목 × ~120 byte) 대비 충분한 여유.
        // overflow 시 tryEmitNext가 FAIL_OVERFLOW 반환 → silent drop (backpressure 정책).
        private const val BACKPRESSURE_BUFFER_SIZE = 1024

        /** SystemEvent actor — P1 catalog (spec §6.1 emit point #4·#5). */
        private const val SYSTEM_EVENT_ACTOR = "KisWebSocketBridge"

        // 체결가는 H0UNCNT0 단일 채널(KRX+NXT 통합 — ADR 0124).
        internal const val TR_ID_UNIFIED_TICK = "H0UNCNT0"
        internal const val TR_ID_UNIFIED_ORDERBOOK = "H0UNASP0"
        internal const val TR_ID_UNIFIED_MARKET_STATUS = "H0UNMKO0"
        private const val TR_TYPE_SUBSCRIBE = "1"
        private const val TR_TYPE_UNSUBSCRIBE = "2"

        /**
         * WS_DISCONNECTED 알림 강등 grace window (초) — 2026-06-04 점검 사이클 #4.
         *
         * KIS 서버는 매시 정각(11:00·12:00·…) onComplete로 연결을 끊지만 시스템은 0~2초 내
         * 재연결+재구독에 성공한다(운영 evidence). 이 정상 회복 패턴이 매시 WARN→P2 Slack 알림으로
         * 노이즈화되던 것을 막기 위해, disconnect 발생 시 즉시 WARN을 발사하지 않고 이 window 후
         * 재연결 여부를 재평가한다:
         *   - window 내 재연결 성공(session.connected=true) → INFO severity emit (S-2 SLO 관측·audit 보존, Slack 0)
         *   - window 후에도 미회복 → WARN severity emit (정상 P2 알림)
         * grace window는 watchdog 백오프 1·2차(0s·2s)보다 충분히 길고, 운영 회복 관측(0~2초)을 cover한다.
         */
        private const val DISCONNECT_ALERT_GRACE_SECONDS = 10L

        /**
         * ADR 0114 §10-3 R2-C1 — `orderbook:top:{code}` 1호가 캐시 TTL(보수적 짧게).
         *
         * 호가는 H0UNASP0 tick으로 초 단위 갱신되므로 TTL을 짧게 둬 stale 캐시가 진입가에 쓰이는 risk를 줄인다.
         * EntryPricePolicy가 quoteAsOf(=ts) 기반 stale(default 3초)을 한 번 더 거르므로, TTL은 그보다 약간 긴
         * 10초로 둔다(WS 잠깐 끊김에도 직전 호가 1회 fallback 여지 + 그래도 EntryPricePolicy stale gate가 최종 차단).
         */
        private val ORDERBOOK_TOP_TTL: Duration = Duration.ofSeconds(10)

        /**
         * 최근 unsubscribe 전송 이력 상한 (결함③ 진단 관측 — [recentUnsubscribes]).
         * OPSP8997 invalid tr_type 응답 직전 발사된 해제 메시지를 cover할 만큼만(소규모 ring).
         */
        private const val UNSUB_HISTORY_MAX = 40
    }

    private val log = LoggerFactory.getLogger(KisWebSocketBridge::class.java)

    // 재연결 지수 백오프 딜레이 (ms) — ADR 0048 §2-4-B
    private val reconnectDelaysMs = longArrayOf(0L, 2000L, 5000L, 10000L, 30000L)

    private val kstZone = ZoneId.of("Asia/Seoul")

    // WS_DISCONNECTED 알림 강등 grace window (ms) — 2026-06-04 점검 사이클 #4 (companion 상수 default).
    // @Volatile var: 단위 테스트에서 reflection으로 단축(예: 100ms)해 분기 동작만 검증 가능하도록 인스턴스 필드화.
    // 운영 값은 DISCONNECT_ALERT_GRACE_SECONDS(10초)→ms 고정 — 매시 정각 자동 회복(0~2초)을 충분히 cover.
    @Volatile
    private var disconnectAlertGraceMs = DISCONNECT_ALERT_GRACE_SECONDS * 1000L

    // E-1: unsubscribe ACK 대기용 latch (destroy 시 2s 대기)
    // ADR 0076 E-1 — unsubscribe 메시지 send 후 ACK 수신 or 2s timeout 후 close
    // destroy() 진입 시 새 CountDownLatch(1)로 교체, handleMessage에서 isUnsubscribeAck 시 countDown
    private val pendingUnsubAckLatch = AtomicReference<java.util.concurrent.CountDownLatch?>(null)

    // hotfix 2026-05-20 — KIS WS AES key 저장 (trId별 iv·key 매핑).
    // 구독 SUCCESS 응답 `output.iv/key` 추출 후 trIdToAesKey[trId]에 저장 → encrypted RealtimeData (H0STCNI 등) 복호화 시 사용.
    // KIS spec: H0STCNI0/9 (체결통보)는 항상 AES-256-CBC 암호화 — iv 16 byte UTF-8, key 32 byte UTF-8.
    private data class KisWsAesKey(
        val iv: String,
        val key: String,
    )

    private val trIdToAesKey = ConcurrentHashMap<String, KisWsAesKey>()

    /**
     * 체결통보(H0STCNI0/H0STCNI9) WS 데이터 메시지 수신 누적 카운트 — 영구 진단 (2026-06-10 P1 회귀 fix).
     *
     * ### 사유: "안 오는지(수신0) vs 버려지는지(드랍)" 구분
     * 2026-06-08 회귀 조사 시 체결통보 수신 자체 흔적이 없어 "KIS 미발송 vs 우리 드랍" 판정이 불가했다
     * (시세 H0UNCNT0는 정상 수신 → receiveFlux·handleMessage는 살아있는데 H0STCNI만 0).
     * 본 카운터는 handleMessage가 H0STCNI 분기에 진입(=KIS가 데이터 메시지를 실제 발송)할 때마다 증가하며,
     * [handleExecutionNotice]가 listener forward 전 INFO로 누적값을 남긴다. 이후 동일 회귀 재발 시
     * "수신 카운트 0(=KIS 미발송 또는 endpoint 불일치)"과 "수신 후 복호화/forward 실패(드랍)"를 즉시 가른다.
     *
     * AtomicLong 사용 사유: receiveFlux(Reactor thread) 단일이지만 가시성·원자성 보장 + 멀티 thread 안전.
     */
    private val executionNoticeReceivedCount = AtomicLong(0L)

    /**
     * 24h 롤링 재연결 시각(epoch ms) 윈도우 — 관리자 스냅샷 `reconnects24h`용 (read-only 진단).
     *
     * onConnected의 재연결 분기(attemptCount > 0)에서 1건 기록. 스냅샷 조회 시 24h 초과분 prune.
     * 구독/재연결 로직과 무관 — 표시 전용 카운트만 누적한다.
     * 동시성: ConcurrentLinkedDeque — onConnected(Reactor thread) write + 스냅샷 publisher(Scheduled thread) read.
     */
    private val reconnectTimestampsMs = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    private val session = KisWebSocketSession()
    private val scheduler = Executors.newScheduledThreadPool(1)

    // 호가 구독 종목코드 Set (H0UNASP0 — KRX+NXT 통합 호가)
    private val orderbookSubscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 장운영/VI 구독 종목코드 Set (H0UNMKO0 — KRX+NXT 통합 장운영정보)
    private val marketStatusSubscribedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 시간외 단일가 구독 종목코드 Set (H0STOUP0 — ADR 0076 Phase 3 B-2 — 16:00~18:00 KST)
    // KIS-first 출처: open-trading-api examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
    // tr_id = "H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
    // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
    private val overtimeSinglePriceCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Sink: 메시지 전송용 (subscribe/unsubscribe 메시지를 WS에 보냄)
    private var sendSink: Sinks.Many<String>? = null
    private var wsDisposable: Disposable? = null
    private var heartbeatWatchdog: ScheduledFuture<*>? = null
    private var noTickWatchdog: ScheduledFuture<*>? = null

    /**
     * 종목별 마지막 체결 tick 수신 시각(epoch ms) — 무tick watchdog 기준선 (결함 ② — 403870 silent 누락).
     *
     * - subscribe(신규 논리 구독) 시 "구독 발사 시각"으로 seed → watchdog 윈도우가 구독 시점부터 시작
     *   (seed 0이면 부팅 직후 전종목이 즉시 stale 오탐).
     * - [handleUnifiedTickData]·[handleOvertimeSinglePriceTickData] tick 수신마다 갱신.
     * - unsubscribe·shutdown 시 제거.
     *
     * ConcurrentHashMap: Reactor 수신 thread(put) + watchdog scheduler thread(read) 다중 접근 — thread-safe 의무.
     */
    private val lastTickAtMsByCode = ConcurrentHashMap<String, Long>()

    /**
     * 무tick WARN 이미 발화한 종목 set — 같은 stale 윈도우에서 매 watchdog cycle 중복 WARN 스팸 차단.
     * tick이 다시 들어오면([recordTickReceived]) 제거 → 재무장(다음 stale 시 1회 다시 WARN).
     */
    private val noTickWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * 최근 unsubscribe 전송 이력 (결함③ 진단 관측 전용 — 부작용 0).
     *
     * ## 배경 (2026-06-15 REAL 세션 실측)
     * DETAIL 종목 EXIT/15분 idle 해제 시 H0UNASP0(+10:42엔 H0UNCNT0) unsubscribe가 KIS `OPSP8997
     * invalid tr_type`(응답 header `tr_id=null`)으로 거부됐다(3회 재현: 10:42 005930, 11:08 326030).
     * trType 값("0")·`buildSubscribeMessage` 직렬화는 전송 원문 기준 정상처럼 보였으나,
     * 코드 상단 KIS-first 계약과 실측 응답이 해제 trType="2" 필요성을 가리켰다. 응답이 tr_id=null이라
     * 어떤 code/trId 해제가 거부됐는지 추적이 불가했다.
     *
     * ## 이 deque 의 역할
     * unsubscribe 메시지를 sink emit 직전에 (epochMs, code, trId, trType, rawJson) 으로 기록한다.
     * OPSP8997 invalid tr_type 응답 수신 시 [dumpRecentUnsubscribesForDiagnosis]가 본 이력을 덤프 →
     * "직전에 어떤 해제 메시지가 나갔는지 + 그 wire JSON 이 정상인지(동시 emit race로 깨졌는지)"를 눈으로
     * 확정할 수 있게 한다. 원인이 전송 race로 확정되면 그때 별도 fix.
     *
     * ConcurrentLinkedDeque + [UNSUB_HISTORY_MAX] 상한(addLast/pollFirst trim) — emit thread + 수신 thread
     * 다중 접근 thread-safe. 관측 전용이므로 trim race로 1~2건 오차는 무해.
     */
    private val recentUnsubscribes = ConcurrentLinkedDeque<UnsubscribeTrace>()

    @Volatile
    private var pollingFallbackActive = AtomicBoolean(false)

    // 재연결 중복 방지 guard — watchdog 트리거 후 doFinally 완료 전 중복 dispose 차단.
    private val reconnecting = AtomicBoolean(false)
    private val gracefulReconnectSuppressUntilEpochMillis = AtomicLong(0L)

    /**
     * ★ 유령 재연결 루프 단일화 guard (2026-06-05 P0 — KIS WS 유령 재연결 루프 fix).
     *
     * ### 운영 evidence (2026-06-05 라이브 실측)
     * - OPSP8996/ALREADY IN USE 128건 + reconnect attempt 115까지 누적 후 재카운트 반복.
     * - 핵심 모순: 재연결 스케줄 "delay=30000ms"인데 attempt가 1~5초 간격으로 증가.
     * - 동시에 1m 캔들은 FRESH(실시간 수신 정상) — 살아있는 데이터 연결과 별개로 유령 세션들이
     *   각자 connect 시도 → 매 시도마다 동일 appkey로 KIS 접속 → OPSP8996 거부 → doFinally → 또 스케줄.
     *
     * ### root cause (메커니즘)
     * 기존 [reconnecting] guard는 watchdog dispose 경로만 보호한다. 다음 두 경로는 무방비:
     * 1. doFinally가 reconnecting.set(false)를 **scheduleReconnect() 호출 직전**에 해제 → 동시 진입한
     *    여러 doFinally(onComplete+onError 쌍 또는 다수 stale 세션)가 각자 scheduleReconnect()를
     *    독립 발사 → 각 발사가 새 doConnect()로 새 Disposable+Sink+WS 세션 생성(같은 appkey).
     * 2. doConnect()·scheduleConnect()에 "살아있는 세션 존재" 차단 가드 부재 → 정상 세션이 살아있어도
     *    stale doFinally가 새 connect를 발사 → KIS OPSP8996 → handleOpsp8996 dispose → doFinally → 증식.
     *
     * ### fix (단일화)
     * doConnect() 진입을 CAS(false→true)로 단일화한다. 동시에 단 1개의 connect/reconnect attempt만
     * in-flight 허용. terminal(doFinally)에서 false 해제 → 다음 attempt 가능.
     * 추가로 [connect]·[scheduleReconnect]·[doConnect]는 살아있는 세션(session.connected) 존재 시
     * 신규 connect 시도 자체를 차단해 유령 세션 발사를 원천 봉쇄한다.
     *
     * AtomicBoolean 사용 사유: scheduler thread + Reactor doFinally thread 다중 진입 — 가시성·원자성 의무.
     */
    private val connectInFlight = AtomicBoolean(false)

    /**
     * ★ scheduleReconnect 단일 발사 guard (2026-06-05 P0 — 유령 루프 fix).
     *
     * 동시 진입한 여러 doFinally(이중 단절·다수 stale 세션)가 각자 scheduleReconnect()를 발사하면
     * pending reconnect future가 N개 누적된다(유령 루프 증식의 직접 원인). CAS(false→true)로 단 1개의
     * pending reconnect만 허용하고, scheduleConnect 직전 false 해제한다(다음 terminal에서 재무장).
     */
    private val reconnectScheduled = AtomicBoolean(false)

    /**
     * P1 spec §6.1 emit point #4·#5 — WS_DISCONNECTED·WS_RECONNECTED·WS_CONNECTED correlationId 묶기.
     *
     * disconnect handler 진입 시 UUID 생성·set, reconnect handler 진입 시 getAndSet(null)로 회수.
     * - getAndSet(null) → 비어있으면 첫 connect (connectCount=0)이면 WS_CONNECTED emit (fresh UUID), 아니면 RECONNECTED emit (fresh UUID)
     * - getAndSet(null) → UUID 있으면 동일 correlationId로 WS_RECONNECTED emit (UI 그룹 표시)
     *
     * AtomicReference 사용 사유: disconnect doFinally vs handshake handler가 별도 Reactor thread 가능성 →
     * 가시성·원자성 보장 의무.
     *
     * hotfix 2026-05-21 — 거짓 끊김 표시 fix (사용자 dogfooding 1차):
     *   evidence: id=145·146·147에서 disconnect 2건 연속(onComplete+onError) 발생 시 두번째 set이 첫번째 cid를 덮어
     *   첫번째 RECONNECTED 발사 0건. id=150에서 disconnect 후 stale cid stuck 가능성.
     *   fix: emit point #4에서 기존 cid가 살아있으면 WS_RECONNECT_LOST emit으로 분리 audit + 새 cid set.
     *        emit point #5에서 cid가 null이고 connectCount > 0 (재연결)이면 fresh UUID로 RECONNECTED emit 보장.
     *
     * hotfix 2026-05-21 — startup 첫 connect WS_CONNECTED emit (사용자 dogfooding 2차):
     *   evidence: 재배포마다 startup 직전 WS_DISCONNECTED 잔재가 DB 최신 system_event로 stuck →
     *   첫 connect emit skip이면 frontend KisWsHealthIndicator가 영구 🔴 표시.
     *   fix: 첫 connect (connectCount==0)에서 WS_CONNECTED emit으로 변경 — action 분리 (RECONNECTED ≠ CONNECTED audit 정합).
     *        frontend useKisWsHealthState는 이미 CONNECTED_ACTIONS = {WS_RECONNECTED, WS_CONNECTED} 인식.
     */
    private val currentDisconnectCorrelationId = AtomicReference<String?>(null)

    /**
     * hotfix 2026-05-21 — connect handler 진입 횟수 카운터.
     *
     * 첫 connect(=0)와 재연결(≥1) 구분 — emit point #5 분기 매트릭스:
     *   - attemptCount==0 (첫 connect) → WS_CONNECTED emit (fresh UUID) — startup·재배포 audit + frontend stale 해소
     *   - attemptCount > 0 (재연결) + cid 회수 성공 → WS_RECONNECTED emit (재사용 cid — 1쌍 그룹)
     *   - attemptCount > 0 (재연결) + cid 회수 실패 → WS_RECONNECTED emit (fresh UUID — race fallback)
     * (cid stuck or race condition으로 disconnect cid 회수 실패해도 RECONNECTED emit 누락 없음 — 사용자 거짓 끊김 표시 차단.)
     *
     * AtomicInteger 사용 사유: connect handler가 Reactor thread에서 호출되며 reconnect/graceful 경로 다중 진입 가능 →
     * 가시성·원자성 보장 의무. session.reconnectCount는 onConnected에서 0 reset되므로 별도 누적 카운터 필요.
     */
    private val connectCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // F-1: OPSP8996 self-heal retry 카운터 (subscribe 응답 메시지 단위).
    // ADR 0076 §3 F-1 — 1회 retry(1초 대기) 후 2회 fail 시 ERROR + skip.
    // 연결 수립 시(onConnected) 0 초기화 — 새 연결마다 카운터 리셋.
    private val opsp8996RetryCount =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // polling fallback 콜백 (backend-core WatchlistPollingWorker와 연동)
    @Volatile
    var onPollingFallbackStart: (() -> Unit)? = null

    @Volatile
    var onPollingFallbackStop: (() -> Unit)? = null

    /**
     * ADR 0084 P0-3 — WS 재연결 직후 발사 hook (외부 코드 리뷰 결함 #4 fix).
     *
     * 발사 시점: 새 wsSession handshake 성공(`session.onConnected()`) 직후 + 재구독 메시지 발사 직전.
     * **첫 연결**과 **재연결 양쪽 모두 발사** — 첫 연결 시점에도 직전 사용자 disconnect 동안 발생한 체결이 있을 수 있음.
     *
     * 등록자: [KisExecutionBackfillService] — KisOrderInquireCcldHttpAdapter 호출로 누락 체결 backfill.
     * 미등록 상태도 graceful (null check) — 본 hook 부재 시 backfill 0건 + WARN log (운영 audit).
     *
     * 호출 횟수 / 빈도:
     * - 첫 연결 시점 — 1회
     * - watchdog reconnect (30s/60s timeout 후) — 매 재연결 1회
     * - graceful reconnect (KIS 키 변경) — 1회
     *
     * Rate Limit 영향 (ADR 0037):
     * - 본 hook은 1회 KIS REST inquire-daily-ccld 호출 trigger → backfill worker가 내부 처리
     * - WS 폭주 시 재연결 잦음 → backfill 호출 빈도 ↑ → KIS rate limit hit 가능 risk → backlog §62 측정 후 throttle 검토
     */
    @Volatile
    var onReconnect: (() -> Unit)? = null

    /**
     * OPSP0008(MAX SUBSCRIBE OVER) 구독 거부 시 호출되는 graceful fallback hook (ADR 0123 §3-4 R1).
     *
     * 거부된 (code, trId) 채널을 ledger 회계 보정 + subscribedCodes 정합 처리한 직후 발사.
     * 등록자(KisWsRejectFallbackWiring 등 R1 wiring)가 gap-fill(`SymbolDetailMinuteGapFillService.submitIfNeeded`)
     * + quote polling(`WatchlistPollingWorker.add`)을 연결한다.
     *
     * 본 hook은 KisWebSocketBridge가 reconciliation/polling 컴포넌트를 직접 의존하지 않도록(헌법 §6 + onReconnect 패턴)
     * 콜백으로 분리한다. 미등록 시 graceful(null check) — gap-fill/polling 0 + ledger 회계 보정·WARN은 수행됨.
     *
     * 파라미터: (code, trId) — 거부된 채널 식별자.
     */
    @Volatile
    var onSubscribeRejected: ((code: String, trId: String) -> Unit)? = null

    /**
     * SUBSCRIBE SUCCESS 후 종목 단위 체결 tick 0건 silent 감지 시 호출되는 fallback hook.
     *
     * 기존 무tick watchdog는 관측 WARN만 수행해 Redis flush 등으로 `market:candle:current:*:1m`이 사라진 뒤
     * KIS가 특정 종목 tick을 계속 보내지 않으면 1m DB/전략 평가가 멈췄다. 본 hook은 WS bridge가
     * reconciliation/KIS REST 컴포넌트를 직접 의존하지 않고, wiring이 저빈도 1m gap-fill을 연결하도록 한다.
     *
     * 파라미터: (code, trId, ageSeconds) — silent 채널 식별자와 마지막 tick 이후 경과 초.
     */
    @Volatile
    var onNoTickDetected: ((code: String, trId: String, ageSeconds: Long) -> Unit)? = null

    private fun slotLedger(): kr.spdaewoon.magicjar.consumer.ingestion.WsSlotBudgetLedger? = slotLedgerProvider.ifAvailable

    private fun sendTickSubscribe(
        approvalKey: String,
        code: String,
        trType: String,
    ) {
        val msg = buildSubscribeMessage(approvalKey, code, trType = trType, trId = TR_ID_UNIFIED_TICK)
        sendSink?.tryEmitNext(msg)
    }

    /**
     * KIS WS에 종목 구독 (H0UNCNT0 통합 체결가 단일 채널 — ADR 0124).
     * slot 여유 확인은 SubscriptionRouter 책임 — 여기서는 단순 subscribe 메시지 송신.
     */
    override fun subscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        // G-1: approval_key null 시 subscribe skip — invalid key로 KIS에 발사 차단.
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS subscribe skip — approval_key 발급 불가 (REAL 자격증명 미등록): codes={}", codes)
                return
            }
        // ★ watchlist 1m stale race fix (2026-06-04 점검 사이클 #3):
        //   handshake 완료 전(session.connected=false 또는 sendSink null)에는 KIS로 즉시 emit하지 않는다.
        //   미연결 윈도우에 tryEmitNext한 메시지는 sink 미생성/미구독 상태에서 폐기될 수 있고,
        //   그 경우 onConnected 재구독 스냅샷 타이밍에 따라 해당 종목이 KIS에 영구 미발사 → 1m candle stale.
        //   부팅 시 subscribedCodes 등록만 수행하고 실제 발사는 onConnected 재구독에 단일 위임한다.
        //   연결 후(런타임 add — watchlist 추가·promotion 등)에는 즉시 emit (sink live).
        val live = session.connected && sendSink != null
        codes.forEach { code ->
            val newLogicalSubscription = session.subscribedCodes.add(code)
            if (newLogicalSubscription) {
                seedTodayCandle(code)
                slotLedger()?.register(trId = TR_ID_UNIFIED_TICK, code = code)
                // 무tick watchdog 윈도우 seed — 구독 발사 시각 기준(첫 tick 도착 전까지 stale 카운트 시작점).
                lastTickAtMsByCode[code] = System.currentTimeMillis()
                noTickWarned.remove(code)
                if (live) {
                    sendTickSubscribe(approvalKey, code, trType = TR_TYPE_SUBSCRIBE)
                    log.info("KIS WS subscribe: code={} trId={}", code, TR_ID_UNIFIED_TICK)
                } else {
                    log.info("KIS WS subscribe 등록(연결 전 — onConnected 재구독 위임): code={} trId={}", code, TR_ID_UNIFIED_TICK)
                }
            }
        }
    }

    /**
     * KIS WS 종목 구독 해제 (H0UNCNT0 통합 체결가 단일 채널 — ADR 0124).
     */
    override fun unsubscribe(codes: List<String>) {
        if (codes.isEmpty()) return
        // G-1: approval_key null 시 unsubscribe skip — set 상태만 정리.
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS unsubscribe — approval_key 발급 불가, local state만 정리: codes={}", codes)
                codes.forEach { code ->
                    session.subscribedCodes.remove(code)
                    slotLedger()?.release(trId = TR_ID_UNIFIED_TICK, code = code)
                    lastTickAtMsByCode.remove(code)
                    noTickWarned.remove(code)
                }
                return
            }
        codes.forEach { code ->
            if (session.subscribedCodes.contains(code)) {
                // 결함③ 진단: emit 직전 전송 원문 로깅 + 이력 기록.
                emitUnsubscribeMessage(approvalKey, code, trId = TR_ID_UNIFIED_TICK)
                slotLedger()?.release(trId = TR_ID_UNIFIED_TICK, code = code)
                session.subscribedCodes.remove(code)
                lastTickAtMsByCode.remove(code)
                noTickWarned.remove(code)
                log.info("KIS WS unsubscribe: code={} trId={}", code, TR_ID_UNIFIED_TICK)
            }
        }
    }

    override fun subscribedCodes(): Set<String> = session.subscribedCodes.toSet()

    /**
     * ACTIVE 전략 degraded feed 판정용 read-only tick age.
     *
     * null이면 해당 JVM 세션에서 아직 H0UNCNT0/H0STOUP0 기준선이 없다는 뜻이다.
     * 호출자는 이 값을 근거로만 KIS 구독 상태를 변경하지 않는다.
     */
    fun lastTickAgeSeconds(code: String): Long? {
        val lastMs = lastTickAtMsByCode[code] ?: return null
        return ((System.currentTimeMillis() - lastMs).coerceAtLeast(0L)) / 1000L
    }

    /**
     * 관리자 실시간 상태 스냅샷용 — 체결가 demand가 점유하는 tr_id (ADR 0124 단일 채널).
     *
     * 체결가는 H0UNCNT0 단일 채널이므로 항상 H0UNCNT0을 반환한다(read-only 표시용).
     */
    fun activeTickTrId(): String = TR_ID_UNIFIED_TICK

    /**
     * 관리자 실시간 상태 스냅샷용 — WS 세션·체결통보 read-only 진단 투영 (구독/재연결 로직 변경 0).
     *
     * - connected      : 현재 세션 살아있음(session.connected).
     * - sessionCid     : 살아있는 세션 UUID(onConnected 시 발급, 끊김 시 null).
     * - reconnects24h  : 최근 24h 재연결 횟수(reconnectTimestampsMs prune 후 count).
     * - lastConnectedAtMs: 마지막 연결 수립 시각(epoch ms).
     * - executionNotice: 체결통보(H0STCNI0/9) 자동 구독 상태 — subscribe 조건(연결 + userId 존재) 투영.
     *     subscribed = session.connected && defaultUserId.isNotBlank() (subscribeExecutionNoticeIfPossible 조건 정합).
     *     mode·userIdMasked는 표시 전용(maskUserId 재사용).
     *
     * publisher(Scheduled thread)에서만 호출 — 모든 read는 thread-safe 자료구조(AtomicReference·Deque) 기반.
     */
    fun wsStatusSnapshot(): WsStatusSnapshot {
        val now = System.currentTimeMillis()
        val cutoff = now - TimeUnit.HOURS.toMillis(24)
        // 24h 초과분 prune (read 시점 lazy housekeeping — peekFirst null 또는 cutoff 이상까지).
        while (true) {
            val head = reconnectTimestampsMs.peekFirst() ?: break
            if (head < cutoff) reconnectTimestampsMs.pollFirst() else break
        }
        val trId = executionNoticeTrId()
        val executionSubscribed = session.connected && defaultUserId.isNotBlank()
        return WsStatusSnapshot(
            connected = session.connected,
            sessionCid = session.sessionCid.get(),
            reconnects24h = reconnectTimestampsMs.size,
            lastConnectedAtMs = session.lastConnectedAtMs.get(),
            executionSubscribed = executionSubscribed,
            executionMode = magicjarMode,
            executionTrId = trId,
            executionUserIdMasked = if (defaultUserId.isBlank()) null else maskUserId(defaultUserId),
        )
    }

    /**
     * KIS WS 통합 호가 구독 (H0UNASP0).
     * KIS-first 출처: open-trading-api domestic_stock_functions_ws.py asking_price_total() tr_type="1"
     */
    override fun subscribeOrderbook(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS orderbook subscribe skip — approval_key 발급 불가: codes={}", codes)
                return
            }
        codes.forEach { code ->
            if (!orderbookSubscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_SUBSCRIBE, trId = TR_ID_UNIFIED_ORDERBOOK)
                sendSink?.tryEmitNext(msg)
                orderbookSubscribedCodes.add(code)
                slotLedger()?.register(trId = TR_ID_UNIFIED_ORDERBOOK, code = code) // ADR 0123 R1 — 호가 채널 점유
                log.info("KIS WS orderbook subscribe: code={} trId={}", code, TR_ID_UNIFIED_ORDERBOOK)
            }
        }
    }

    /**
     * KIS WS 통합 호가 구독 해제 (H0UNASP0).
     */
    override fun unsubscribeOrderbook(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS orderbook unsubscribe — approval_key 발급 불가, local state만 정리: codes={}", codes)
                codes.forEach { code ->
                    orderbookSubscribedCodes.remove(code)
                    slotLedger()?.release(trId = TR_ID_UNIFIED_ORDERBOOK, code = code) // ADR 0123 R1
                }
                return
            }
        codes.forEach { code ->
            if (orderbookSubscribedCodes.contains(code)) {
                // 결함③ 진단(H0UNASP0 OPSP8997 주 대상): emit 직전 전송 원문 로깅 + 이력 기록.
                emitUnsubscribeMessage(approvalKey, code, trId = TR_ID_UNIFIED_ORDERBOOK)
                orderbookSubscribedCodes.remove(code)
                slotLedger()?.release(trId = TR_ID_UNIFIED_ORDERBOOK, code = code) // ADR 0123 R1
                log.info("KIS WS orderbook unsubscribe: code={} trId={}", code, TR_ID_UNIFIED_ORDERBOOK)
            }
        }
    }

    override fun subscribedOrderbookCodes(): Set<String> = orderbookSubscribedCodes.toSet()

    /**
     * KIS WS 통합 장운영/VI 구독 (H0UNMKO0).
     * KIS-first 출처: open-trading-api domestic_stock_functions_ws.py market_status_total() tr_type="1"
     */
    override fun subscribeMarketStatus(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS market-status subscribe skip — approval_key 발급 불가: codes={}", codes)
                return
            }
        codes.forEach { code ->
            if (!marketStatusSubscribedCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_SUBSCRIBE, trId = TR_ID_UNIFIED_MARKET_STATUS)
                sendSink?.tryEmitNext(msg)
                marketStatusSubscribedCodes.add(code)
                slotLedger()?.register(trId = TR_ID_UNIFIED_MARKET_STATUS, code = code)
                log.info("KIS WS market-status subscribe: code={} trId={}", code, TR_ID_UNIFIED_MARKET_STATUS)
            }
        }
    }

    /**
     * KIS WS 통합 장운영/VI 구독 해제 (H0UNMKO0).
     */
    override fun unsubscribeMarketStatus(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS market-status unsubscribe — approval_key 발급 불가, local state만 정리: codes={}", codes)
                codes.forEach { code ->
                    marketStatusSubscribedCodes.remove(code)
                    slotLedger()?.release(trId = TR_ID_UNIFIED_MARKET_STATUS, code = code)
                }
                return
            }
        codes.forEach { code ->
            if (marketStatusSubscribedCodes.contains(code)) {
                // 결함③ 진단: emit 직전 전송 원문 로깅 + 이력 기록.
                emitUnsubscribeMessage(approvalKey, code, trId = TR_ID_UNIFIED_MARKET_STATUS)
                marketStatusSubscribedCodes.remove(code)
                slotLedger()?.release(trId = TR_ID_UNIFIED_MARKET_STATUS, code = code)
                log.info("KIS WS market-status unsubscribe: code={} trId={}", code, TR_ID_UNIFIED_MARKET_STATUS)
            }
        }
    }

    override fun subscribedMarketStatusCodes(): Set<String> = marketStatusSubscribedCodes.toSet()

    /**
     * KIS WS 시간외 단일가 구독 (H0STOUP0 — ADR 0076 Phase 3 B-2).
     *
     * 활성 시간: 16:00~18:00 KST. OvertimeSinglePriceSubscriber가 Scheduled cron으로 호출.
     * slot 여유 확인은 OvertimeSinglePriceSubscriber 책임(OVERTIME_SINGLE_PRICE_MAX=20 기준).
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *   tr_type "1" = 구독 등록 / tr_key = 종목코드
     */
    fun subscribeOvertimeSinglePrice(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS overtime subscribe skip — approval_key 발급 불가: codes={}", codes)
                return
            }
        codes.forEach { code ->
            if (!overtimeSinglePriceCodes.contains(code)) {
                val msg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_SUBSCRIBE, trId = "H0STOUP0")
                sendSink?.tryEmitNext(msg)
                overtimeSinglePriceCodes.add(code)
                log.info("KIS WS subscribe: code={} trId=H0STOUP0", code)
            }
        }
    }

    /**
     * KIS WS 시간외 단일가 구독 해제 (H0STOUP0).
     *
     * 18:00 KST 진입 시 OvertimeSinglePriceSubscriber가 Scheduled cron으로 호출.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   tr_type "2" = 구독 해제
     */
    fun unsubscribeOvertimeSinglePrice(codes: List<String>) {
        if (codes.isEmpty()) return
        val approvalKey =
            getApprovalKey() ?: run {
                log.warn("KIS WS overtime unsubscribe — approval_key 발급 불가, local state만 정리: codes={}", codes)
                codes.forEach { code -> overtimeSinglePriceCodes.remove(code) }
                return
            }
        codes.forEach { code ->
            if (overtimeSinglePriceCodes.contains(code)) {
                // 결함③ 진단: emit 직전 전송 원문 로깅 + 이력 기록.
                emitUnsubscribeMessage(approvalKey, code, trId = "H0STOUP0")
                overtimeSinglePriceCodes.remove(code)
                log.info("KIS WS unsubscribe: code={} trId=H0STOUP0", code)
            }
        }
    }

    /**
     * 현재 시간외 단일가 구독 종목 Set 반환 (OvertimeSinglePriceSubscriber 조회용).
     */
    fun subscribedOvertimeSinglePriceCodes(): Set<String> = overtimeSinglePriceCodes.toSet()

    /**
     * WebSocket 연결 시작 + approval_key 발급 + 기존 구독 종목 재구독.
     * 이미 연결된 상태이면 no-op (idempotent).
     *
     * WatchlistIngestionWorker 또는 RealtimeSubscriptionRouter가 실시간 수요 발견 후 호출.
     *
     * KIS-first 출처:
     * - approval_key endpoint: /oauth2/Approval (KisApprovalAdapter)
     * - WebSocket URL raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/websocket/
     */
    override fun connect() {
        if (session.connected || session.shuttingDown) return
        // ★ 유령 루프 fix (2026-06-05 P0): connect attempt 이미 in-flight면 중복 connect 차단.
        // session.connected는 handshake 완료 후 true이므로, handshake 진행 중(in-flight) 윈도우는
        // connectInFlight로 별도 차단해야 동일 appkey 다중 connect 발사를 막는다.
        if (connectInFlight.get()) {
            log.debug("KIS WS connect skip — connect attempt 이미 in-flight (유령 connect 차단)")
            return
        }
        log.info("KIS WS 연결 시작: url={}", getWsUrl())
        scheduleConnect(delayMs = 0L)
    }

    private fun scheduleConnect(delayMs: Long) {
        if (session.shuttingDown) return
        scheduler.schedule({
            // ★ 유령 루프 fix (2026-06-05 P0): pending reconnect future가 실행 직전 단계에 진입했으므로
            // reconnectScheduled를 해제해 다음 terminal에서 새 재연결 예약이 가능하게 한다(단일성은
            // doConnect()의 connectInFlight CAS + session.connected 가드가 계속 보장).
            reconnectScheduled.set(false)
            if (!session.shuttingDown) {
                doConnect()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun doConnect() {
        // ★ 유령 루프 단일화 fix (2026-06-05 P0):
        // 1) 살아있는 세션 존재 시 신규 connect 발사 차단 — stale doFinally가 정상 세션과 별개로
        //    동일 appkey 재접속을 시도해 OPSP8996(ALREADY IN USE)을 유발하던 유령 루프 원천 봉쇄.
        if (session.connected) {
            log.warn("KIS WS doConnect skip — 이미 살아있는 세션 존재 (유령 connect 차단, OPSP8996 회피)")
            return
        }
        // 2) connect attempt CAS — 동시에 단 1개의 connect/reconnect만 in-flight 허용.
        //    여러 doFinally가 각자 scheduleReconnect→doConnect를 발사해도 CAS 실패분은 즉시 무효화.
        //    terminal(doFinally) 또는 아래 early-return 경로에서 false 해제.
        if (!connectInFlight.compareAndSet(false, true)) {
            log.debug("KIS WS doConnect skip — connect attempt 이미 in-flight (유령 connect 단일화 차단)")
            return
        }

        // ADR 0076 A-1 복원: approval_key invalidate 제거 — 24h TTL Redis cache reuse 의무.
        //
        // [취소된 이전 접근] PR #444 에서 doConnect() 진입마다 invalidate() 호출하여
        // KIS 표준 24h reuse 위반 → OPSP8996 다발 → watchdog 재연결 → 또 invalidate → 무한 루프.
        //
        // [KIS 표준 패턴] KIS-first raw 출처:
        //   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
        //   kis_auth.py auth_ws() — approval_key 1회 발급 + 86,400초(24h) reuse.
        //   KIS 공식 예제에서 매 연결마다 재발급하지 않음.
        //
        // [OPSP8996 해소 대책] F-1 self-heal: subscribe 응답 msg_cd="OPSP8996" 감지 시
        //   1초 대기 후 1회 retry → 2회 fail 시 ERROR + skip (handleMessage 내 처리).
        //   invalidate + 재발급보다 안정적: 24h 내 동일 key 재사용이 KIS 표준이며
        //   OPSP8996은 일시적 세션 중복 상태로, 잠깐 대기 후 retry로 대부분 해소됨.

        // G-1: 자격증명 사전 검증 — null 시 connect skip (KIS auth_ws() None 반환 패턴 정합).
        // KIS-first raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
        // auth_ws() 실패 시 호출자가 None 체크 후 연결하지 않는 것이 KIS 표준.
        // "ERROR_APPROVAL_KEY" 문자열로 KIS에 subscribe 발사하는 silent path 차단 (ADR 0077 §2-1 결함 #1·#3).
        val approvalKey = getApprovalKey()
        if (approvalKey == null) {
            log.warn(
                "KIS WS connect 취소 — approval_key 발급 불가 (REAL 자격증명 미등록). " +
                    "scheduleReconnect 차단. SetupWizard에서 KIS REAL 키 등록 후 재부팅 필요.",
            )
            // 자격증명 미등록 시 scheduleReconnect를 통한 무한 retry 차단:
            // doConnect()는 return하고 doFinally(→scheduleReconnect)를 거치지 않으므로
            // 자격증명 등록 전까지 재시도 없이 WARN 1회로 종료. (J-1 보완 역할)
            connectInFlight.set(false) // ★ in-flight guard 해제 — early-return (유령 루프 fix 정합)
            return
        }

        // backlog §29 #4 — Sinks bufferSize 명시 (reconnect 폭주 시 unbounded growth 차단).
        // Reactor 공식 API: Sinks.UnicastSpec.onBackpressureBuffer는 bufferSize 직접 overload 없음.
        // 대신 Queue 인자 overload로 bounded queue 주입 — Queues.get(1024) = SPSC bounded supplier.
        // 출처: https://projectreactor.io/docs/core/release/api/reactor/util/concurrent/Queues.html
        //       Queues.get(int batchSize) → "an unbounded or bounded Queue Supplier"
        // 1024는 정상 시 ~6 KB 대비 충분한 여유 + reconnect 폭주 상한 hardcap.
        // overflow 시 EmissionResult FAIL_OVERFLOW 반환 → tryEmitNext 호출자가 drop 자동 수행
        // (sendSink?.tryEmitNext는 boolean/EmissionResult 반환 — 별도 drop 정책 없이 silent drop).
        val sink =
            Sinks
                .many()
                .unicast()
                .onBackpressureBuffer<String>(Queues.get<String>(BACKPRESSURE_BUFFER_SIZE).get())
        sendSink = sink

        val wsUrl = getWsUrl()

        wsDisposable =
            wsClient
                .execute(URI(wsUrl)) { wsSession ->
                    // 실제 WebSocket handshake 성공 시점 — 핸들러 람다 진입 = 연결 수립 확인.
                    // (doOnSubscribe는 subscribe() 직후 동기 호출이라 handshake 완료 전임)
                    session.onConnected()
                    opsp8996RetryCount.set(0) // F-1: 새 연결마다 OPSP8996 retry 카운터 초기화
                    pollingFallbackActive.set(false)
                    onPollingFallbackStop?.invoke()
                    log.info("KIS WS 연결 성공 — approval_key 발급 완료")
                    startHeartbeatWatchdog()
                    startNoTickWatchdog()

                    // ★ SystemEvent emit point #5 (P1 spec §6.1) — WS_CONNECTED·WS_RECONNECTED
                    // currentDisconnectCorrelationId.getAndSet(null) — UUID 있으면 disconnect↔reconnect 1쌍 그룹화
                    // (UI 표시 시 correlationId join).
                    //
                    // hotfix 2026-05-21 — fallback UUID 보장 (사용자 dogfooding "거짓 끊김 표시" fix, 1차):
                    // 1) cid null + connectCount==0 (첫 connect): emit skip — disconnect 흔적 없는 첫 connect는 audit 무가치.
                    // 2) cid null + connectCount > 0 (재연결): fresh UUID 생성해서 RECONNECTED emit — 이전 disconnect cid가
                    //    onComplete+onError 2건 연속·doConnect 실패 race로 회수 실패한 경우라도 RECONNECTED 누락 0건 보장.
                    // 3) cid 있음: 그대로 회수해서 동일 cid로 emit (정상 1쌍 그룹).
                    //
                    // hotfix 2026-05-21 — startup 첫 connect WS_CONNECTED emit (사용자 dogfooding "거짓 끊김 영구 잔존" 2차 fix):
                    //   evidence: consumer 재배포 시 startup 직전 WS_DISCONNECTED 잔재가 DB 최신 system_event로 stuck.
                    //   첫 connect (connectCount==0)에서 emit skip이면 frontend KisWsHealthIndicator(useKisWsHealthState)가
                    //   영구 🔴 표시 → 사용자 거짓 끊김 표시 영구 잔존.
                    //   fix: 첫 connect 분기를 WS_CONNECTED emit으로 변경 (action 분리 — RECONNECTED ≠ CONNECTED audit 정합).
                    //   frontend는 이미 CONNECTED_ACTIONS = {WS_RECONNECTED, WS_CONNECTED} 인식 (useKisWsHealthState.ts L51).
                    //
                    // 분기 매트릭스:
                    //   | cid getAndSet | connectCount | emit action       |
                    //   |---------------|--------------|-------------------|
                    //   | non-null      | > 0          | WS_RECONNECTED (recoveredCid 재사용 — 1쌍 그룹) |
                    //   | null          | > 0          | WS_RECONNECTED (fresh UUID — cid 회수 실패 fallback) |
                    //   | null          | == 0         | WS_CONNECTED   (fresh UUID — startup 첫 연결 ★ 본 hotfix) |
                    val attemptCount = connectCount.getAndIncrement()
                    val recoveredCid = currentDisconnectCorrelationId.getAndSet(null)
                    if (attemptCount > 0) {
                        // 관리자 스냅샷 reconnects24h — 재연결 1건 기록 (read-only 진단, 구독 로직 무관).
                        reconnectTimestampsMs.addLast(System.currentTimeMillis())
                        // 재연결 경로 — RECONNECTED emit (recovered or fresh UUID)
                        val reconnectCid = recoveredCid ?: UUID.randomUUID().toString()
                        emitWsEvent(
                            severity = SystemEventSeverity.INFO,
                            action = "WS_RECONNECTED",
                            correlationId = reconnectCid,
                            detailJson =
                                """{"timestamp":"${Instant.now()}","cidRecovered":${recoveredCid != null}}""",
                        )
                    } else {
                        // 첫 connect 경로 — WS_CONNECTED emit (startup·재배포 audit + frontend stale stuck 해소)
                        val firstConnectCid = UUID.randomUUID().toString()
                        emitWsEvent(
                            severity = SystemEventSeverity.INFO,
                            action = "WS_CONNECTED",
                            correlationId = firstConnectCid,
                            detailJson =
                                """{"timestamp":"${Instant.now()}","firstConnect":true}""",
                        )
                    }

                    // ADR 0084 P0-3 — WS 재연결 backfill hook (외부 코드 리뷰 결함 #4 fix).
                    // 재구독 메시지 발사 직전 호출 — disconnect 동안 발생한 체결 통보를 KIS REST inquire-daily-ccld로 보강.
                    // hook 미등록 시 graceful — null check 후 skip.
                    try {
                        onReconnect?.invoke()
                    } catch (e: Exception) {
                        log.error(
                            "KIS WS onReconnect hook 실패 — backfill 건너뜀 (자본 audit risk): err={}",
                            e.message,
                            e,
                        )
                    }
                    // 기존 구독 종목 재구독 (H0UNCNT0 통합 체결가)
                    val existingCodes = session.subscribedCodes.toList()
                    if (existingCodes.isNotEmpty()) {
                        val reconnectMs = System.currentTimeMillis()
                        existingCodes.forEach { code ->
                            val msg = buildSubscribeMessage(approvalKey, code, trType = "1", trId = TR_ID_UNIFIED_TICK)
                            sink.tryEmitNext(msg)
                            // 무tick watchdog 윈도우 재seed — 재구독 시점부터 stale 카운트 시작(이전 stale 흔적 reset).
                            lastTickAtMsByCode[code] = reconnectMs
                            noTickWarned.remove(code)
                        }
                        log.info("KIS WS 재구독 완료: 종목 {}건 trId={}", existingCodes.size, TR_ID_UNIFIED_TICK)
                    }

                    // 체결통보 자동 subscribe (H0STCNI0 REAL / H0STCNI9 MOCK) — ADR 0068 §14-6 + §7-3 LOCK
                    // 사용자별 1 구독 (ADR 0048 §2-1 의무 reserve — 40 slot 한도 산식에 포함)
                    // KIS-first: kws.subscribe(request=ccnl_notice, data=[trenv.my_htsid])
                    //   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/chk_ccnl_notice.py
                    //   tr_key = HTS 사용자 ID (defaultUserId 정합 — magicjar.kis.ws.user-id)
                    // reconnect 시 본 블록 재진입으로 자동 재구독 정합 (별도 상태 set 불필요 — KIS WS 세션 단위 구독).
                    subscribeExecutionNoticeIfPossible(approvalKey, sink)

                    val sendFlux =
                        sink
                            .asFlux()
                            .map { text -> wsSession.textMessage(text) }

                    val receiveFlux =
                        wsSession
                            .receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext { raw -> handleMessage(raw) }
                            .doOnError { err -> log.error("KIS WS 수신 오류: {}", err.message) }
                            .then()

                    wsSession.send(sendFlux).mergeWith(receiveFlux).then()
                }.doOnError { err ->
                    log.warn(
                        "KIS WS 연결 실패 — reconnect scheduled by doFinally: type={} message={}",
                        err.javaClass.simpleName,
                        err.message,
                    )
                }.doFinally { signal ->
                    // doFinally: onComplete·onError·cancel(dispose) 모두 처리.
                    // 이전 doOnTerminate는 cancel(dispose) 시 미호출 → watchdog dispose 후
                    // scheduleReconnect() 0건 결함 원인 (PR #444 후속 hotfix).
                    reconnecting.set(false) // 재연결 중복 방지 guard 해제
                    connectInFlight.set(false) // ★ in-flight guard 해제 (유령 루프 fix) — terminal 시 다음 attempt 허용
                    session.onDisconnected()
                    stopHeartbeatWatchdog()
                    stopNoTickWatchdog()
                    log.warn("KIS WS 연결 종료: signal={}", signal)

                    // ★ SystemEvent emit point #4 (P1 spec §6.1) — WS_DISCONNECTED
                    // shuttingDown=true (destroy 정상 종료)는 emit skip — 운영 사고 아님.
                    // correlationId 새로 생성 → currentDisconnectCorrelationId AtomicReference set →
                    // 다음 reconnect 시 동일 ID 재사용 (UI 그룹 표시).
                    //
                    // hotfix 2026-05-21 — getAndSet으로 이전 cid 회수 + stale 감지 (사용자 dogfooding):
                    // evidence id=145·146 (10:00:01): doFinally가 onComplete + onError 2건 연속 발생 →
                    // 두번째 set이 첫번째 cid(c7ce1e03) 덮어 RECONNECTED 발사 불가.
                    // fix: getAndSet으로 이전 cid 회수 → 있으면 그 cid에 대해 강제 RECONNECTED emit으로 짝 맞춤
                    //      (WS는 실제 종료된 상태이지만 이전 disconnect-reconnect 쌍 audit 닫기 의무 + emit point #5에서
                    //      재진입 후 새 cid emit 보장).
                    val gracefulReconnectCancel =
                        signal == SignalType.CANCEL &&
                            System.currentTimeMillis() <= gracefulReconnectSuppressUntilEpochMillis.get()

                    if (!session.shuttingDown && !gracefulReconnectCancel) {
                        val correlationId = UUID.randomUUID().toString()
                        val stalePrevCid = currentDisconnectCorrelationId.getAndSet(correlationId)
                        if (stalePrevCid != null) {
                            log.warn(
                                "KIS WS doFinally 중복 발생 — 이전 disconnect cid stale 감지 (RECONNECTED 누락 차단): " +
                                    "prevCid={} newCid={} signal={}",
                                stalePrevCid,
                                correlationId,
                                signal,
                            )
                            // ★ WS_RECONNECT_LOST 오탐 정정 (2026-06-04 점검 사이클 #5):
                            //   이전엔 stale cid 감지 즉시 WARN emit → 매시 정각 onComplete+onError 이중 단절 시
                            //   1~2초 내 재구독 성공(회복)인데도 WARN이 남아 Slack 노이즈 재발(18:00·22:00 evidence 3건).
                            //   fix: WS_DISCONNECTED와 동일 grace window 후 재평가 — 회복 시 INFO 강등(LOST 판정 취소),
                            //        진짜 미회복(grace 초과 + 재구독 실패)만 WARN 유지(S-2 SLO 감시 보존).
                            //   재구독 완료 신호 = grace window 시점의 session.connected (onConnected 진입 시 true 전환).
                            scheduleReconnectLostAlert(stalePrevCid, signal.toString())
                        }
                        // ★ WS_DISCONNECTED 알림 강등 (2026-06-04 점검 사이클 #4):
                        //   즉시 WARN emit 대신 grace window 후 재연결 여부를 재평가해 severity를 조건화.
                        //   매시 정각 단절은 0~2초 내 회복되므로 INFO로 강등 → Slack 노이즈 0.
                        //   S-2 SLO 관측을 위해 disconnect 사건 자체는 항상 emit(severity만 분기) — metric/audit 보존.
                        scheduleDisconnectAlert(correlationId, signal.toString())
                        scheduleReconnect()
                    } else if (gracefulReconnectCancel) {
                        log.info("KIS WS graceful reconnect cancel signal — direct reconnect path가 처리하므로 scheduleReconnect skip")
                    }
                }.subscribe(
                    { },
                    { err ->
                        log.warn(
                            "KIS WS 연결 오류 처리 완료 — Reactor onErrorDropped 차단: type={} message={}",
                            err.javaClass.simpleName,
                            err.message,
                        )
                    },
                )
    }

    private fun handleMessage(raw: String) {
        session.onMessageReceived(Clock.System.now())

        when (val parsed = KisWsMessageParser.parse(raw)) {
            is KisWsMessageParser.ParsedMessage.Pingpong -> {
                // KIS-first: kis_auth.py "await ws.pong(raw)" — 수신 raw 그대로 echo 의무
                // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
                sendSink?.tryEmitNext(parsed.rawMessage)
                log.trace("KIS WS PINGPONG keep-alive echo 전송")
            }

            is KisWsMessageParser.ParsedMessage.RealtimeData -> {
                // hotfix 2026-05-20 — encrypted=true (KIS 첫 필드 1) 시 AES-256-CBC 복호화 후 forward.
                // H0STCNI0/9 (체결통보)는 항상 encrypted=true. 평문은 그대로 통과.
                // ★ 체결통보 raw 수신 카운트 (2026-06-10 P1 회귀 진단) — 복호화 전 시점에 잡아
                // "KIS 미발송(0)" vs "복호화/forward 실패(드랍)"를 가른다. AES key 미저장/복호화 실패로
                // 아래에서 return되더라도 본 카운트는 이미 증가해 "수신은 했으나 드랍" 판정이 가능하다.
                if (parsed.trId == "H0STCNI0" || parsed.trId == "H0STCNI9") {
                    val n = executionNoticeReceivedCount.incrementAndGet()
                    log.info(
                        "KIS WS 체결통보 raw 수신 #{}: trId={} encrypted={} rawLen={}",
                        n,
                        parsed.trId,
                        parsed.encrypted,
                        parsed.rawData.length,
                    )
                }
                val data =
                    if (parsed.encrypted) {
                        val aesKey = trIdToAesKey[parsed.trId]
                        if (aesKey == null) {
                            log.error(
                                "KIS WS encrypted 메시지 — AES key 미저장 (구독 SUCCESS 응답 누락): trId={} rawDataPrefix={}",
                                parsed.trId,
                                parsed.rawData.take(40),
                            )
                            return
                        }
                        runCatching { KisWsAesDecryptor.decrypt(parsed.rawData, aesKey.iv, aesKey.key) }
                            .getOrElse { e ->
                                log.error("KIS WS AES 복호화 실패: trId={} err={}", parsed.trId, e.message)
                                return
                            }
                    } else {
                        parsed.rawData
                    }
                when (parsed.trId) {
                    // H0UNCNT0 통합 체결가 — ADR 0124 단일 채널 (KRX+NXT 통합).
                    // KIS-first: examples_llm/domestic_stock/ccnl_total/ccnl_total.py
                    TR_ID_UNIFIED_TICK -> handleUnifiedTickData(data)
                    TR_ID_UNIFIED_ORDERBOOK -> handleOrderbookData(data)
                    // H0UNMKO0 통합 장운영/VI — VI 정책 EXIT_MARKET/ALERT_ONLY 보유 종목 관측.
                    // KIS-first: domestic_stock_functions_ws.py market_status_total()
                    TR_ID_UNIFIED_MARKET_STATUS -> handleMarketStatusData(data)
                    // H0STCNI0(REAL) / H0STCNI9(MOCK) 체결통보 — ADR 0068 §14-6 LOCK
                    // KIS-first 출처: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446 (def ccnl_notice)
                    //   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
                    //   - H0STCNI0 = 실전 체결통보 / H0STCNI9 = 모의 체결통보 (env_dv real/demo 분기)
                    //   - body는 caret(^) delimiter — KisExecutionWsListener.onWebSocketMessage()가 KisExecutionMessage.parseFromCaretDelimited() 위임
                    //   - 흐름 분기는 cntgYn/acptYn/rfusYn 기준 (tr_id 분기 불필요 — listener 내부 §14-6 LOCK)
                    "H0STCNI0", "H0STCNI9" -> handleExecutionNotice(parsed.trId, data)
                    // H0STOUP0 시간외 단일가 체결가 — ADR 0076 Phase 3 B-2 — 16:00~18:00 KST
                    // KIS-first: overtime_ccnl_krx.py tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
                    // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
                    "H0STOUP0" -> handleOvertimeSinglePriceTickData(data)
                    else -> log.debug("KIS WS 알 수 없는 TR_ID: trId={}", parsed.trId)
                }
            }

            is KisWsMessageParser.ParsedMessage.SubscriptionResponse -> {
                val success = KisWsMessageParser.isSubscriptionSuccess(parsed.rawJson)
                if (!success && KisWsMessageParser.isOpsp8996(parsed.rawJson)) {
                    // F-1 + I-1: OPSP8996 "ALREADY IN USE appkey" self-heal retry
                    // ADR 0076 §3 F-1 + ADR 0077 I-1: 1회 retry (1초 대기) → 2회 fail 시 ERROR + reset + skip.
                    // cap 3 초과 시 connection dispose. OPSP8996은 일시적 세션 중복 상태.
                    handleOpsp8996(parsed.rawJson)
                } else if (!success && KisWsMessageParser.isOpsp0008(parsed.rawJson)) {
                    // ADR 0123 R1: OPSP0008 "MAX SUBSCRIBE OVER" — 구독 한도(40) 초과 거부.
                    // ledger 회계 보정 + subscribedCodes 정합 + gap-fill/polling graceful fallback.
                    handleOpsp0008(parsed.rawJson)
                } else if (success) {
                    // I-1: subscribe 성공 시 OPSP8996 retry counter reset — 동일 connection 내 사이클 분리.
                    // ADR 0077 §4-3 결함 #2: counter reset 0 누락으로 attempt 무한 누적되던 결함 fix.
                    if (opsp8996RetryCount.get() != 0) {
                        opsp8996RetryCount.set(0)
                        log.debug("KIS WS subscribe 성공 — OPSP8996 retry counter reset")
                    }
                    // hotfix 2026-05-20 — 구독 SUCCESS 응답에서 AES iv·key 추출 + trIdToAesKey 저장.
                    // H0STCNI0/9 등 encrypted RealtimeData 처리 시 KisWsAesDecryptor에 위임.
                    extractAndStoreAesKey(parsed.rawJson)
                    log.info("KIS WS 구독 응답: success=true json={}", parsed.rawJson.take(200))
                } else if (!success && KisWsMessageParser.isOpsp8997InvalidTrType(parsed.rawJson)) {
                    // 결함③ 진단(2026-06-15): OPSP8997 invalid tr_type — unsubscribe 거부.
                    // 후속 hotfix: 실제 해제 발사는 TR_TYPE_UNSUBSCRIBE("2")로 정정.
                    // 응답 header tr_id=null로 추적 불가 → 최근 unsubscribe 발사 이력을 상관관계 덤프(관측 전용).
                    // ⚠ trType 값은 KIS 공식 정합(해제="0") 확인됨 — 절대 "2"로 변경 금지(회귀). 이번엔 진단만.
                    dumpRecentUnsubscribesForDiagnosis(parsed.rawJson)
                } else if (KisWsMessageParser.isUnsubscribeAck(parsed.rawJson)) {
                    // E-1: unsubscribe ACK 수신 — destroy() 대기 latch 해제
                    // ADR 0076 E-1: destroy() 시 unsubscribe 후 ACK 2s 대기
                    // KIS-first: kis_auth.py isUnSub 판별 + msg_cd OPSP0002
                    // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
                    log.info("KIS WS unsubscribe ACK 수신 — destroy 대기 해제")
                    pendingUnsubAckLatch.get()?.countDown()
                } else {
                    log.info("KIS WS 구독 응답: success={} json={}", success, parsed.rawJson.take(200))
                }
            }

            is KisWsMessageParser.ParsedMessage.Unknown -> {
                log.debug("KIS WS 알 수 없는 메시지: raw={}", raw.take(200))
            }
        }
    }

    private fun handleOrderbookData(rawData: String) {
        val snapshot = KisWsOrderbookParser.parseH0Unasp0(rawData) ?: return
        log.debug("KIS WS orderbook: code={} bids={} asks={}", snapshot.code, snapshot.bids.size, snapshot.asks.size)

        // ADR 0114 §10-3 R2-C1 — 진입 marketable limit 동기 read source(orderbook:top:{code}) write-through.
        // 기존 Kafka/STOMP 흐름 무변경(add-only). EntryQuotePort(RedisEntryQuoteAdapter)가 이 키를 읽는다.
        writeOrderbookTopToRedis(snapshot)

        // Kafka market.orderbook.v1 publish
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to snapshot.code,
                        "ts" to snapshot.ts.toString(),
                        "bids" to snapshot.bids.map { mapOf("price" to it.price.toPlainString(), "quantity" to it.quantity) },
                        "asks" to snapshot.asks.map { mapOf("price" to it.price.toPlainString(), "quantity" to it.quantity) },
                        "sourceTrId" to TR_ID_UNIFIED_ORDERBOOK,
                        "totalAskQuantity" to snapshot.totalAskQuantity,
                        "totalBidQuantity" to snapshot.totalBidQuantity,
                        "overtimeTotalAskQuantity" to snapshot.overtimeTotalAskQuantity,
                        "overtimeTotalBidQuantity" to snapshot.overtimeTotalBidQuantity,
                        "anticipatedPrice" to snapshot.anticipatedPrice?.toPlainString(),
                        "anticipatedQuantity" to snapshot.anticipatedQuantity,
                        "anticipatedVolume" to snapshot.anticipatedVolume,
                        "anticipatedChange" to snapshot.anticipatedChange?.toPlainString(),
                        "anticipatedChangeSign" to snapshot.anticipatedChangeSign,
                        "anticipatedChangeRate" to snapshot.anticipatedChangeRate?.toPlainString(),
                        "accumulatedVolume" to snapshot.accumulatedVolume,
                        "stockDealClassCode" to snapshot.stockDealClassCode,
                    ),
                )
            kafkaTemplate.send(KafkaTopics.Market.ORDERBOOK, snapshot.code, payload)
        } catch (e: Exception) {
            log.error("KIS WS orderbook Kafka publish 실패: code={} error={}", snapshot.code, e.message)
        }
    }

    private fun handleMarketStatusData(rawData: String) {
        val fallbackCode = marketStatusSubscribedCodes.singleOrNull()
        val event = KisWsMarketStatusParser.parseH0Unmko0(rawData, fallbackCode = fallbackCode) ?: return
        log.info(
            "KIS WS market-status: code={} viActive={} viCls={} ovtmViCls={} exch={}",
            event.code,
            event.isViActive,
            event.viClassCode,
            event.overtimeViClassCode,
            event.exchangeClassCode,
        )

        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "code" to event.code,
                        "ts" to event.ts.toString(),
                        "sourceTrId" to TR_ID_UNIFIED_MARKET_STATUS,
                        "isViActive" to event.isViActive,
                        "tradeHaltYn" to event.tradeHaltYn,
                        "tradeSuspensionReason" to event.tradeSuspensionReason,
                        "marketOpenClassCode" to event.marketOpenClassCode,
                        "anticipatedMarketOpenClassCode" to event.anticipatedMarketOpenClassCode,
                        "marketTreatmentClassCode" to event.marketTreatmentClassCode,
                        "divisionApplyClassCode" to event.divisionApplyClassCode,
                        "instrumentStatusClassCode" to event.instrumentStatusClassCode,
                        "viClassCode" to event.viClassCode,
                        "overtimeViClassCode" to event.overtimeViClassCode,
                        "exchangeClassCode" to event.exchangeClassCode,
                        "rawFieldCount" to event.rawFieldCount,
                    ),
                )
            kafkaTemplate.send(KafkaTopics.Market.STATUS, event.code, payload)
        } catch (e: Exception) {
            log.error("KIS WS market-status Kafka publish 실패: code={} error={}", event.code, e.message)
        }
    }

    /**
     * H0UNCNT0 통합 체결가 tick 처리 — KRX+NXT를 한 채널로 받는 단일 경로 (ADR 0124).
     *
     * 1. Redis quote:last:{code} 갱신 (ADR 0014 T1 cache)
     * 2. Redis candle:current:{code}:{interval} HMSET 갱신 (ADR 0029 확장)
     * 3. Kafka market.tick.v1 publish
     */
    private fun handleUnifiedTickData(rawData: String) {
        val tick = KisWsMessageParser.parseH0Uncnt0(rawData) ?: return
        log.debug("KIS WS unified tick: code={} price={} vol={} source={}", tick.code, tick.price, tick.volume, tick.source)

        recordTickReceived(tick.code)
        writeToRedis(tick)
        liveCandleUpdater.update(tick)
        publishTickEvent(tick)
    }

    /**
     * 종목별 tick 수신 기록 — 무tick watchdog 기준선 갱신 + WARN 재무장 (결함 ② — 403870 silent 누락).
     *
     * tick이 다시 들어오면 noTickWarned에서 제거해 다음 stale 윈도우에 1회 다시 WARN 가능(stale→복구→재stale 추적).
     */
    private fun recordTickReceived(code: String) {
        lastTickAtMsByCode[code] = System.currentTimeMillis()
        noTickWarned.remove(code)
    }

    /**
     * H0STOUP0 시간외 단일가 체결가 tick 처리.
     *
     * 활성 시간: ADR 0076 Phase 3 B-2 — 16:00~18:00 KST.
     * TickEvent.source = "live-overtime" 로 H0UNCNT0와 구분.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
     *   overtime_ccnl_krx() tr_id="H0STOUP0" — 국내주식 시간외 실시간체결가 (KRX) [실시간-042]
     *   필드 인덱스: H0STCNT0(ccnl_krx)와 동일 위치 매핑 — [0]=종목코드 [2]=현재가 [12]=체결거래량 [33]=영업일자
     */
    private fun handleOvertimeSinglePriceTickData(rawData: String) {
        val tick = KisWsMessageParser.parseH0Stoup0(rawData) ?: return
        log.debug(
            "KIS WS overtime tick: code={} price={} vol={} source={}",
            tick.code,
            tick.price,
            tick.volume,
            tick.source,
        )
        // 동일 처리 흐름 — Redis update + candle 갱신 + Kafka publish
        recordTickReceived(tick.code)
        writeToRedis(tick)
        liveCandleUpdater.update(tick)
        publishTickEvent(tick)
    }

    /**
     * 체결통보 tr_id 분기 — REAL → H0STCNI0 / MOCK → H0STCNI9.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/ccnl_notice.py
     *   env_dv="real" → H0STCNI0 / env_dv="demo" → H0STCNI9
     *
     * magicjar.mode 정합:
     *   MOCK → H0STCNI9 (모의 체결통보)
     *   REAL → H0STCNI0 (실전 체결통보)
     *   기본값 MOCK (dev/staging 안전 default).
     */
    private fun executionNoticeTrId(): String = if (RuntimeModeParser.isRealMode(magicjarMode)) "H0STCNI0" else "H0STCNI9"

    /**
     * 체결통보 자동 subscribe — 사용자 본인 HTS 사용자 ID(defaultUserId) 1 구독.
     *
     * 사용자 메모리 정합 (project_kis_key_realonly_for_data):
     *   "시세·캔들·호가 등 정보 수집은 모두 실전 키. 모의/실전 분기는 주문 액션에만."
     *   → 단, 체결통보 WS는 자본 영향 이벤트 알림 (주문 액션 결과 통보)이므로 magicjarMode 분기 의무.
     *   MOCK 모드에서 H0STCNI0를 사용하면 모의 주문의 체결통보가 수신되지 않음.
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/chk_ccnl_notice.py
     *   kws.subscribe(request=ccnl_notice, data=[trenv.my_htsid])
     *   - tr_key = HTS 사용자 ID (defaultUserId — magicjar.kis.ws.user-id 설정값)
     *   - tr_type "1" = 구독 등록 / "2" = 해제
     *   - header: approval_key + tr_type + custtype="P"
     *
     * ADR 0048 §2-1 정합:
     *   H0STCNI0/H0STCNI9 = 사용자별 1 slot (의무 reserve).
     *   40 slot 한도에서는 본 메서드의 1 slot을 reserved로 먼저 반영하고, 나머지 채널은 WsSlotBudgetLedger가 점유를 계산한다.
     *   legacy 1(체결통보) + 30(이중 체결가) + 10(호가) = 41 산식은 ADR 0123에서 정정됨.
     *
     * defaultUserId 비어있으면 skip — magicjar.kis.ws.user-id 미설정 환경(qa fixture 등) 보호.
     */
    private fun subscribeExecutionNoticeIfPossible(
        approvalKey: String,
        sink: Sinks.Many<String>,
    ) {
        if (defaultUserId.isBlank()) {
            log.warn(
                "KIS WS 체결통보 subscribe skip — magicjar.kis.ws.user-id 미설정 " +
                    "(SetupWizard에서 HTS 사용자 ID 등록 후 재부팅 필요).",
            )
            return
        }
        val trId = executionNoticeTrId()
        // tr_key = HTS 사용자 ID (KIS-first: trenv.my_htsid)
        // buildSubscribeMessage(code = defaultUserId) — code 파라미터 자리에 사용자 ID 주입 (KIS payload tr_key 의미)
        val msg = buildSubscribeMessage(approvalKey, defaultUserId, trType = "1", trId = trId)
        val emitted = sink.tryEmitNext(msg)
        log.info(
            "KIS WS 체결통보 subscribe: trId={} userId={} mode={} emit={}",
            trId,
            maskUserId(defaultUserId),
            magicjarMode,
            emitted,
        )
    }

    /**
     * 체결통보 unsubscribe — graceful shutdown (destroy) 시 발사.
     *
     * KIS-first 출처: ccnl_notice.py tr_type "2" = 구독 해제
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/ccnl_notice.py
     */
    private fun unsubscribeExecutionNoticeIfPossible(approvalKey: String) {
        if (defaultUserId.isBlank()) return
        val trId = executionNoticeTrId()
        val msg = buildSubscribeMessage(approvalKey, defaultUserId, trType = TR_TYPE_UNSUBSCRIBE, trId = trId)
        sendSink?.tryEmitNext(msg)
        log.info(
            "KIS WS 체결통보 unsubscribe: trId={} userId={}",
            trId,
            maskUserId(defaultUserId),
        )
    }

    /**
     * KIS WS H0STCNI0/H0STCNI9 체결통보 처리 — KisExecutionWsListener 위임 (ADR 0068 §14-6 LOCK).
     *
     * KIS-first 출처:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     *   def ccnl_notice() L389~L446 — 국내주식 실시간체결통보
     *   - tr_id H0STCNI0 = 실전 / H0STCNI9 = 모의 (env_dv real/demo 분기)
     *   - body는 caret(^) delimiter — 26 컬럼 매핑 LOCK (ADR 0068 §14-6 표 정합)
     *   - 흐름 분기는 KisExecutionWsListener.processMessage() 내부 cntgYn/acptYn/rfusYn 기준 — tr_id 분기 불필요
     *
     * graceful fallback:
     *   - executionListenerProvider 미합류 시(qa fixture 등) log.warn 후 skip.
     *   - 정상 운영 시 always present — null 분기는 안전망 (헌법 §6 + ADR 0066 ObjectProvider 패턴).
     *
     * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
     */
    private fun handleExecutionNotice(
        trId: String,
        rawData: String,
    ) {
        val listener = executionListenerProvider.getIfAvailable()
        if (listener == null) {
            log.warn(
                "KIS WS 체결통보 수신 — KisExecutionWsListener 미합류 (Phase 1 미완): trId={} bodyLen={}",
                trId,
                rawData.length,
            )
            return
        }
        try {
            listener.onWebSocketMessage(rawData)
        } catch (e: Exception) {
            log.error(
                "KIS WS 체결통보 listener dispatch 실패: trId={} err={}",
                trId,
                e.message,
                e,
            )
        }
    }

    /**
     * Redis quote:last:{code} 갱신.
     * quote:last 는 화면/실시간 보조 캐시라 5분만 유지한다. 장외·휴장·저유동 종목에서 오래된 tick 이
     * REST 스냅샷을 덮지 않도록 API read-side 도 동일 5분 freshness gate 를 적용한다.
     * dogfooding 결함 1 fix: priceChange·changeRate·changeSign 필드 포함 (등락률 싱크).
     * KIS-first: PRDY_VRSS[4]·PRDY_CTRT[5]·PRDY_VRSS_SIGN[3] 출처
     *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
     */
    private fun writeToRedis(tick: TickEvent) {
        try {
            val data =
                mutableMapOf<String, Any?>(
                    "price" to tick.price.toPlainString(),
                    "volume" to tick.volume,
                    "ts" to tick.ts.toString(),
                    "source" to tick.source,
                )
            // 등락 필드 — null 이면 키 자체를 포함하지 않음 (polling source 호환)
            tick.priceChange?.let { data["priceChange"] = it.toPlainString() }
            tick.changeRate?.let { data["changeRate"] = it.toPlainString() }
            tick.changeSign?.let { data["changeSign"] = it }

            val json = objectMapper.writeValueAsString(data)
            redisTemplate
                .opsForValue()
                .set(
                    RedisKeyRegistry.Market.quoteLast(tick.code),
                    json,
                    java.time.Duration.ofMinutes(5),
                )
        } catch (e: Exception) {
            log.error("KIS WS Redis 쓰기 실패: code={} error={}", tick.code, e.message)
        }
    }

    /**
     * ADR 0114 §10-3 R2-C1 — H0UNASP0 1호가(매도1·매수1·매도잔량1)를 `orderbook:top:{code}` Redis write-through.
     *
     * 진입(BUY) marketable limit 산정의 **동기 read source**다(`EntryQuotePort` → `RedisEntryQuoteAdapter`가 소비).
     * 전체 10단계는 Kafka `market.orderbook.v1`로 계속 흐르고(무변경), 본 캐시는 진입 산정에 필요한 1호가만 담는다.
     *
     * TTL은 보수적으로 짧게(`ORDERBOOK_TOP_TTL`) 둔다 — 호가는 H0UNASP0 tick으로 초 단위 갱신되므로 stale 캐시가
     * 진입가에 쓰이는 risk를 줄인다. EntryPricePolicy가 quoteAsOf(=ts)로 stale을 한 번 더 거른다(default 3초).
     * 호가 WS 미구독 종목(ADR 0048 LRU·§10-3 NXT slot 포화)은 이 키가 없어 EntryQuotePort가 REST fallback으로 넘어간다.
     *
     * bids/asks가 비면(파싱상 0건) write skip — 빈 호가로 진입가 산정 오염 방지.
     */
    private fun writeOrderbookTopToRedis(snapshot: kr.spdaewoon.magicjar.domain.market.OrderbookSnapshot) {
        try {
            val ask1 = snapshot.asks.firstOrNull() ?: return
            val bid1 = snapshot.bids.firstOrNull() ?: return
            val data =
                mapOf(
                    "ask1" to ask1.price.toPlainString(),
                    "bid1" to bid1.price.toPlainString(),
                    "askQty1" to ask1.quantity.toString(),
                    "bidQty1" to bid1.quantity.toString(),
                    "ts" to snapshot.ts.toString(),
                    "sourceTrId" to TR_ID_UNIFIED_ORDERBOOK,
                    "anticipatedPrice" to snapshot.anticipatedPrice?.toPlainString(),
                    "anticipatedQuantity" to snapshot.anticipatedQuantity?.toString(),
                    "anticipatedVolume" to snapshot.anticipatedVolume?.toString(),
                    "anticipatedChange" to snapshot.anticipatedChange?.toPlainString(),
                    "anticipatedChangeSign" to snapshot.anticipatedChangeSign,
                    "anticipatedChangeRate" to snapshot.anticipatedChangeRate?.toPlainString(),
                )
            redisTemplate
                .opsForValue()
                .set(
                    RedisKeyRegistry.Market.orderbookTop(snapshot.code),
                    objectMapper.writeValueAsString(data),
                    ORDERBOOK_TOP_TTL,
                )
        } catch (e: Exception) {
            log.error("KIS WS orderbook:top Redis 쓰기 실패: code={} error={}", snapshot.code, e.message)
        }
    }

    /**
     * 구독 수립 시점 호출 — `candle:today:{code}:1d`(+`candle:current:{code}:1d`) Hash를 공식 KIS 당일값으로 시드/재보정.
     *
     * [TodayCandleSeedPort] 계약 구현. RealtimeSubscriptionRouter가 owner(DETAIL·HOLDING·WATCHLIST·STRATEGY·PENDING_BUY)
     * 무관하게 WS 구독이 새로 수립되는 지점에서 호출 → 늦은/간헐 구독에도 open=공식 9시 시가, 고/저·거래량=공식 당일값.
     *
     * 시드 후 WS 틱은 [updateLiveCandleHash]에서 seeded=true 분기로 합류:
     *  - open LOCK(공식 시가 보존), 고/저는 공식 baseline 위 확장만, volume = acmlBase + WS CNTG_VOL 델타.
     *
     * graceful:
     *  - 장전(08:00 전)·주말에는 KIS 일봉 output1의 전일값을 오늘 bar로 올리지 않는다.
     *  - REST 어댑터 부재(fixture)·magicjar.kis.ws.user-id 미설정·공식값 결손 시 시드 no-op (mock 합성 금지).
     *  - 시드 없는 1d Hash는 tick 하나로 만들지 않고 DB/quote fallback에 맡긴다.
     *  - 다른 bar(어제 stale)이면 시드값으로 새 bar 세팅(open/고저/거래량 공식값으로 교체).
     */
    override fun seedTodayCandle(code: String) {
        val now = Instant.now()
        if (todayCandleSeedPreOpenGuardEnabled && TodayCandleSeedWindow.shouldSkipSeed(now, kstZone)) {
            log.info(
                "[TodayCandleSeed] 장전/휴장 seed skip: code={} nowKst={} integratedOpen=08:00",
                code,
                now.atZone(kstZone),
            )
            return
        }

        val metricsPort =
            priceMetricsPortProvider.ifAvailable
                ?: run {
                    log.debug("[TodayCandleSeed] REST metrics 어댑터 부재 — 시드 skip: code={}", code)
                    return
                }
        val seedUserId = resolveSeedUserId()
        if (seedUserId.isBlank()) {
            log.debug("[TodayCandleSeed] REST 자격증명 userId resolve 불가 — 시드 skip: code={}", code)
            return
        }
        try {
            val metrics = metricsPort.fetchPriceMetrics(seedUserId, code)
            val open = metrics.open
            val high = metrics.high
            val low = metrics.low
            val close = metrics.currentPrice
            val acmlVol = metrics.volume
            // 공식 OHLC 결손 시 시드 skip — mock·부분 합성 금지(헌법 가드). 다음 시드/틱에 재시도.
            if (open == null || high == null || low == null || close == null) {
                log.warn(
                    "[TodayCandleSeed] 공식 OHLC 결손 — 시드 skip: code={} open={} high={} low={} close={}",
                    code,
                    open,
                    high,
                    low,
                    close,
                )
                return
            }
            // ── KIS 영업일자 정합 가드 (새벽 유령 일봉 근본 fix) ───────────────────────────────
            // 자정~개장 전 구간엔 KIS inquire-daily-itemchartprice output1 OHLCV가 **직전 영업일 세션값**을
            // 반환한다. 서버 시계로 startTs를 "오늘"로 찍으면 어제 OHLCV가 오늘 진행 중 캔들로 합성돼
            // 차트에 유령 캔들이 보인다(실측: 093370/005930 — 6/10 DB행 복제). KIS-first 정책상 영업일자
            // 판단도 서버 시계가 아닌 KIS 데이터 기준이어야 한다 → output2[] 최신 행 stck_bsop_date 사용.
            val today = LocalDate.now(kstZone).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            val bsopDate = metrics.businessDate
            when {
                bsopDate == null -> {
                    // 영업일자 결손(output2 부재/파싱 실패) — mock 금지 원칙대로 시드 skip. purge도 안 함
                    // (유령인지 정상인지 판정 근거 없음 → live 틱 정상 bar를 잘못 지우지 않도록 보수적).
                    log.warn("[TodayCandleSeed] KIS 영업일자 결손 — 시드 skip(purge 안 함): code={}", code)
                    return
                }
                bsopDate != today -> {
                    // 개장 전·휴장일 — KIS 세션값이 아직 오늘 것이 아님. 시드 skip + 기존 유령 키 방어적 purge.
                    log.info(
                        "[TodayCandleSeed] KIS 영업일자({}) != 오늘({}) — 개장 전/휴장 시드 skip: code={}",
                        bsopDate,
                        today,
                        code,
                    )
                    purgeGhostSeed(code, today)
                    return
                }
                // else: bsopDate == today → 장중. 기존 시드 로직 그대로(장중 회귀 0).
            }
            // 자기 치유: 영업일자 != 오늘이면 위에서 skip되지만, 09:00 개장 후 KIS 영업일자가 오늘로
            // 바뀌면 RealtimeSubscriptionRouter의 1분 demand 갱신 → scheduleSeed(seedCooldownMs=60s)가
            // 본 메서드를 재호출해 이 분기에 진입, 즉시 정상 시드한다(치유 지연 상한 ≈ 60s).
            val startTs =
                LocalDate
                    .now(kstZone)
                    .atStartOfDay(kstZone)
                    .toInstant()
                    .toString()
            writeSeedHash(RedisKeyRegistry.Market.candleToday1d(code), startTs, open, high, low, close, acmlVol ?: 0L, Duration.ofHours(24))
            writeSeedHash(
                RedisKeyRegistry.Market.candleCurrent(code, "1d"),
                startTs,
                open,
                high,
                low,
                close,
                acmlVol ?: 0L,
                Duration.ofDays(2),
            )
            log.info(
                "[TodayCandleSeed] 공식 KIS 당일값 시드: code={} open={} high={} low={} close={} acmlVol={}",
                code,
                open.toPlainString(),
                high.toPlainString(),
                low.toPlainString(),
                close.toPlainString(),
                acmlVol,
            )
        } catch (e: Exception) {
            // 시드 실패는 graceful — 1d tick-only 합성 없이 DB/quote fallback (구독 자체는 정상).
            log.warn("[TodayCandleSeed] 시드 실패(DB/quote fallback): code={} err={}", code, e.message)
        }
    }

    /**
     * 시드 REST 호출 userId resolve — REAL 자격증명 보유 user 우선, 부재 시 ws.user-id fallback.
     * (user_kis_credential은 개인용 single-user 전제 — 첫 행 사용. 둘 다 없으면 blank → 시드 skip.)
     */
    private fun resolveSeedUserId(): String {
        val repo = credentialRepositoryProvider.ifAvailable ?: return defaultUserId
        return try {
            repo
                .findAllByAccountType("REAL")
                .firstOrNull()
                ?.id
                ?.userId ?: defaultUserId
        } catch (e: Exception) {
            log.debug("[TodayCandleSeed] credential resolve 실패 — ws.user-id fallback: {}", e.message)
            defaultUserId
        }
    }

    /**
     * 시드 Hash write — [updateLiveCandleHash] 필드 포맷 정합 + seeded 마커.
     * seeded=true / acmlBase=공식 누적거래량 / wsVolDelta=0 (이후 WS 틱이 델타 누적).
     */
    private fun writeSeedHash(
        key: String,
        startTs: String,
        open: java.math.BigDecimal,
        high: java.math.BigDecimal,
        low: java.math.BigDecimal,
        close: java.math.BigDecimal,
        acmlVol: Long,
        ttl: Duration,
    ) {
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                "interval" to "1d",
                "open" to open.toPlainString(),
                "high" to high.toPlainString(),
                "low" to low.toPlainString(),
                "close" to close.toPlainString(),
                "volume" to acmlVol.toString(),
                "startTs" to startTs,
                "ts" to startTs,
                "source" to "kis-seed",
                "updatedAt" to Instant.now().toString(),
                "seeded" to "true",
                "acmlBase" to acmlVol.toString(),
                "wsVolDelta" to "0",
            ),
        )
        redisTemplate.expire(key, ttl)
    }

    /**
     * 개장 전/휴장 시드 skip 시 기존 유령 시드 Hash 방어적 purge.
     *
     * 자정 직후 부팅·재구독에서 (이전 가드 부재 시점에) 어제 OHLCV가 "오늘" startTs로 박혀버린
     * candle:today:{code}:1d / candle:current:{code}:1d 유령 키를 제거한다.
     *
     * 안전 조건 (둘 다 충족해야 DEL):
     *  - startTs == 오늘 KST 00:00 (= 유령이 오늘로 위장한 케이스만. 어제/다른 bar는 별 path가 정리).
     *  - seeded == "true" (공식 시드가 만든 Hash만. live 틱 기반 bar는 1d에서 seeded=false라 보존 — updateLiveCandleHash 정합).
     *
     * @param today KIS 영업일자 비교에 쓴 오늘(yyyyMMdd, KST) — startTs 비교는 동일 zone instant로 환산.
     */
    private fun purgeGhostSeed(
        code: String,
        today: String,
    ) {
        val todayStartTs =
            LocalDate
                .now(kstZone)
                .atStartOfDay(kstZone)
                .toInstant()
                .toString()
        for (key in listOf(RedisKeyRegistry.Market.candleToday1d(code), RedisKeyRegistry.Market.candleCurrent(code, "1d"))) {
            val h = redisTemplate.opsForHash<String, String>().entries(key)
            if (h.isEmpty()) continue
            val startTs = h["startTs"] ?: h["ts"]
            val seeded = h["seeded"]
            if (startTs == todayStartTs && seeded == "true") {
                redisTemplate.delete(key)
                log.info(
                    "[TodayCandleSeed] 유령 시드 키 purge: key={} startTs={} today={} (영업일자 미도래)",
                    key,
                    startTs,
                    today,
                )
            }
        }
    }

    /**
     * Kafka market.tick.v1 publish.
     * dogfooding 결함 1 fix: priceChange·changeRate·changeSign 필드 포함.
     * STOMP forwarder(MarketTickStompForwarder)가 payload를 그대로 forward하므로
     * 이 payload에 등락 필드를 넣으면 /topic/market.tick.{code} 에도 자동 전파됨.
     */
    private fun publishTickEvent(tick: TickEvent) {
        try {
            val data =
                mutableMapOf<String, Any?>(
                    "code" to tick.code,
                    "price" to tick.price.toPlainString(),
                    "volume" to tick.volume,
                    "ts" to tick.ts.toString(),
                    "source" to tick.source,
                    "stale" to tick.stale,
                )
            tick.priceChange?.let { data["priceChange"] = it.toPlainString() }
            tick.changeRate?.let { data["changeRate"] = it.toPlainString() }
            tick.changeSign?.let { data["changeSign"] = it }
            // R1-A 체결강도 전파 (ADR 0117 §3-R1) — null이면 key 생략 (등락 필드 패턴 1:1).
            // 소비처(MarketTickStompForwarder·PositionStopLossTickWatcher)는 Map::class로 역직렬화 → 모르는 key 무시(하위호환).
            tick.tradeIntensity?.let { data["tradeIntensity"] = it.toPlainString() }
            tick.turnoverAccum?.let { data["turnoverAccum"] = it.toPlainString() }

            val payload = objectMapper.writeValueAsString(data)
            kafkaTemplate.send(KafkaTopics.Market.TICK, tick.code, payload)
        } catch (e: Exception) {
            log.error("KIS WS Kafka publish 실패: code={} error={}", tick.code, e.message)
        }
    }

    /**
     * 재연결 스케줄링 — 지수 백오프 (ADR 0048 §2-4-B).
     * 1차: 즉시, 2차: 2s, 3차: 5s, 4차: 10s, 5차+: 30s
     */
    private fun scheduleReconnect() {
        // ★ 유령 루프 fix (2026-06-05 P0):
        // 1) 살아있는 세션 존재 시 재연결 스케줄 자체를 차단 — stale doFinally가 정상 세션이 살아있는데도
        //    재연결을 발사해 유령 세션을 증식시키던 직접 원인 봉쇄.
        if (session.connected) {
            log.debug("KIS WS scheduleReconnect skip — 이미 살아있는 세션 존재 (유령 재연결 차단)")
            return
        }
        // 2) 단일 발사 CAS — 동시에 진입한 여러 doFinally(이중 단절·다수 stale 세션) 중 단 1개만 통과.
        //    CAS 실패분은 즉시 무효화 → pending reconnect future N개 누적(유령 루프 증식) 차단.
        if (!reconnectScheduled.compareAndSet(false, true)) {
            log.debug("KIS WS scheduleReconnect skip — 재연결 이미 예약됨 (유령 루프 단일화 차단)")
            return
        }
        val attempt = session.incrementReconnect()
        val delayIdx = (attempt - 1).coerceIn(0, reconnectDelaysMs.size - 1)
        val delayMs = reconnectDelaysMs[delayIdx]
        log.warn("KIS WS 재연결 스케줄: attempt={} delay={}ms", attempt, delayMs)

        // 30초 이상 연속 실패 시 polling fallback 활성화
        if (delayMs >= pollingFallbackTimeoutSeconds * 1000 && !pollingFallbackActive.get()) {
            log.warn("KIS WS 재연결 30s 초과 — polling fallback 활성화")
            pollingFallbackActive.set(true)
            onPollingFallbackStart?.invoke()
        }

        scheduleConnect(delayMs)
    }

    /**
     * heartbeat watchdog timeout 반환 (REAL canary F3).
     *
     * ADR 0076 D-1에서는 10s 고정이 장마감·시간외 자연 무신호 재연결 스팸을 만들 수 있어
     * 정규장 30s / 정규장 외 60s 하드코딩으로 늘렸었다. 그러나 REAL canary에서 PINGPONG 0 ghost session이
     * 2시간 동안 감지되지 않은 사건이 있었으므로, 현재 운영 기본은 설정값(기본 10s)을 그대로 따른다.
     */
    internal fun currentHeartbeatTimeoutSeconds(): Long = heartbeatTimeoutSeconds

    /**
     * Heartbeat watchdog — 무신호 시 reconnect 트리거 (ADR 0048 §2-4-A + REAL canary F3).
     *
     * timeout은 `magicjar.kis.ws.heartbeat-timeout-seconds` 설정값(기본 10s)을 따른다.
     * reconnecting guard: CAS(false→true) 성공한 단 1회만 dispose 호출.
     * doFinally에서 reconnecting.set(false) 해제 → 다음 연결 후 watchdog 재작동 가능.
     *
     * watchdog 주기: 10s 고정 (체크 빈도) — timeout은 currentHeartbeatTimeoutSeconds().
     */
    private fun startHeartbeatWatchdog() {
        stopHeartbeatWatchdog()
        heartbeatWatchdog =
            scheduler.scheduleAtFixedRate({
                if (!session.connected || session.shuttingDown) return@scheduleAtFixedRate
                val lastMsg = session.lastMessageAt.get() ?: return@scheduleAtFixedRate
                val elapsed = Clock.System.now().epochSeconds - lastMsg.epochSeconds
                val timeoutSec = currentHeartbeatTimeoutSeconds()
                if (elapsed > timeoutSec && session.subscribedCodes.isNotEmpty()) {
                    // reconnecting CAS: 이미 재연결 중이면 중복 dispose 차단.
                    if (reconnecting.compareAndSet(false, true)) {
                        log.warn(
                            "KIS WS heartbeat 무신호 {}s (timeout={}s) — 재연결 트리거 (dispose 호출)",
                            elapsed,
                            timeoutSec,
                        )
                        wsDisposable?.dispose()
                    } else {
                        log.debug("KIS WS heartbeat 무신호 {}s — 재연결 이미 진행 중, skip", elapsed)
                    }
                }
            }, 10L, 10L, TimeUnit.SECONDS) // 체크 주기 10s 고정 (timeout은 동적)
    }

    private fun stopHeartbeatWatchdog() {
        heartbeatWatchdog?.cancel(false)
        heartbeatWatchdog = null
    }

    /**
     * 종목별 무tick watchdog (결함 ② — 403870 H0UNCNT0 silent 누락 가드, 헌법 §11-2).
     *
     * heartbeat watchdog는 **세션 전체** 무신호(PINGPONG/모든 tick 0)를 보지만, 일부 종목만 SUBSCRIBE
     * SUCCESS 후 프레임 0건인 경우(403870)는 다른 종목 tick이 흐르므로 heartbeat가 살아있어 감지 사각이다.
     * 본 watchdog는 **종목 단위**로 "구독 SUCCESS 후 [noTickWatchdogSeconds]초간 체결 프레임 0"을 정규장
     * 세션(08:00~20:00 KST · [CandleSession.isInIntegratedSession]) 중에만 주기 점검해 WARN으로 노출한다.
     *
     * - 정규장 외(야간·주말)는 자연 무거래이므로 점검 skip(오탐 방지).
     * - 종목별 1 stale 윈도우당 WARN 1회([noTickWarned] 차단) — tick 복구 시 재무장.
     * - 본 watchdog는 **관측 전용**(재구독·dispose 등 부작용 0) — silent 누락을 "보이게" 만드는 것이 1차 목적.
     *   복구 액션(재구독/polling 전환)은 운영자 판단 또는 후속 사이클의 별도 결정 사항(보수적 회귀 보강).
     *
     * 점검 주기 = max(10s, noTickWatchdogSeconds/4) — 임계의 1/4 빈도(과도한 wakeup 방지). 0 이하면 비활성.
     */
    private fun startNoTickWatchdog() {
        stopNoTickWatchdog()
        if (noTickWatchdogSeconds <= 0L) {
            log.info("KIS WS 무tick watchdog 비활성 (noTickWatchdogSeconds={})", noTickWatchdogSeconds)
            return
        }
        val periodSec = (noTickWatchdogSeconds / 4).coerceAtLeast(10L)
        noTickWatchdog =
            scheduler.scheduleAtFixedRate({
                runCatching { runNoTickWatchdogCheck() }
                    .onFailure { e -> log.warn("KIS WS 무tick watchdog 점검 실패(graceful): err={}", e.message) }
            }, periodSec, periodSec, TimeUnit.SECONDS)
        log.info(
            "KIS WS 무tick watchdog 시동: 임계={}s 점검주기={}s (정규장 세션 한정)",
            noTickWatchdogSeconds,
            periodSec,
        )
    }

    private fun stopNoTickWatchdog() {
        noTickWatchdog?.cancel(false)
        noTickWatchdog = null
    }

    /**
     * 무tick watchdog 1회 점검 — 구독 종목 중 [noTickWatchdogSeconds] 초과 무tick 종목 WARN.
     *
     * internal: 단위 테스트에서 시각 의존 없이 직접 호출해 분기(정규장 게이트·임계 초과·중복 차단) 검증.
     */
    internal fun runNoTickWatchdogCheck() {
        if (!session.connected || session.shuttingDown) return
        if (noTickWatchdogSeconds <= 0L) return
        // 정규장(통합 세션 08:00~20:00 KST) 외에는 자연 무거래 → 오탐 방지로 점검 skip.
        val nowKst = Instant.now().atZone(kstZone)
        val minOfDay = nowKst.hour * 60 + nowKst.minute
        if (!CandleSession.isInIntegratedSession(minOfDay)) return

        val nowMs = System.currentTimeMillis()
        val thresholdMs = noTickWatchdogSeconds * 1000L
        val subscribed = session.subscribedCodes.toList()
        for (code in subscribed) {
            val lastMs = lastTickAtMsByCode[code] ?: continue
            val ageMs = nowMs - lastMs
            if (ageMs >= thresholdMs) {
                val ageSeconds = ageMs / 1000L
                runCatching {
                    onNoTickDetected?.invoke(code, TR_ID_UNIFIED_TICK, ageSeconds)
                }.onFailure { e ->
                    log.warn(
                        "KIS WS 무tick fallback hook 실패(graceful): code={} trId={} err={}",
                        code,
                        TR_ID_UNIFIED_TICK,
                        e.message,
                    )
                }
                // 종목별 1 윈도우당 1회만 WARN(중복 스팸 차단). tick 복구 시 recordTickReceived가 재무장.
                if (noTickWarned.add(code)) {
                    log.warn(
                        "KIS WS 무tick 감지 — 구독 SUCCESS 후 {}초간 H0UNCNT0 체결 프레임 0건 (silent 누락 의심 · §11-2): " +
                            "code={} trId={} ageSeconds={}",
                        noTickWatchdogSeconds,
                        code,
                        TR_ID_UNIFIED_TICK,
                        ageSeconds,
                    )
                }
            }
        }
    }

    /**
     * KIS 키 변경 시 graceful reconnect (ADR 0074 §2-2-3 + ADR 0048 §3 정합).
     *
     * 흐름:
     * 1. 기존 WS 연결 종료 (dispose + shuttingDown 플래그는 false 유지 — shutdown 아님)
     * 2. 500ms 대기 (connection close propagation 보장)
     * 3. 신규 연결 시작 (doConnect — approval_key 재발급 + 활성 구독 종목 재구독)
     *
     * userId 파라미터: 로깅 식별용. 현재 단일 사용자 환경(헌법 §1)에서 실제 분기 없음.
     * approval_key 재발급: KisApprovalAdapter 내부 Redis cache가 Phase 1에서 DEL 완료 상태이므로
     *   doConnect → getApprovalKey 호출 시 자동으로 재발급됨 (idempotent 안전).
     *
     * AI 호출 0 — T-O 트랙 룰 5 정합.
     */
    fun reconnectGracefully(userId: String) {
        if (session.shuttingDown) {
            log.warn("[KisKeyChanged] WS shutdown 중 — reconnect 취소: userId={}", maskUserId(userId))
            return
        }
        log.info("[KisKeyChanged] WS graceful reconnect 시작 — userId={}", maskUserId(userId))

        // 1. 기존 연결 종료 (shuttingDown은 false 유지)
        reconnecting.set(false) // graceful reconnect는 독립 경로 — watchdog guard 초기화
        stopHeartbeatWatchdog()
        stopNoTickWatchdog()
        gracefulReconnectSuppressUntilEpochMillis.set(System.currentTimeMillis() + 2000L)
        wsDisposable?.dispose()
        sendSink?.tryEmitComplete()
        session.onDisconnected()

        // 2. 500ms 대기 — connection close propagation
        try {
            Thread.sleep(500L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // 3. 신규 연결 (기존 subscribedCodes 유지 → doConnect에서 재구독)
        session.reconnectCount.set(0)
        doConnect()

        log.info(
            "[KisKeyChanged] WS reconnect 완료 — userId={} subscribedCodes={}",
            maskUserId(userId),
            session.subscribedCodes.size,
        )
    }

    /**
     * F-1 + I-1: OPSP8996 "ALREADY IN USE appkey" self-heal handler.
     *
     * ADR 0076 §3 F-1 + ADR 0077 I-1 정합:
     * - 1회 retry: 1초 대기 후 동일 subscribe 메시지 재전송 요청 (재구독 트리거).
     * - 2회 fail 시: ERROR + counter reset 0 + skip (다음 watchdog cycle 재연결 대기).
     *
     * I-1: retry cap 3회 — KIS 표준 KISWebSocket max_retries=3 정합.
     * - KIS-first 출처: open-trading-api examples_user/kis_auth.py KISWebSocket.__init__(max_retries=3)
     * - raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     * - attempt > 3: connection dispose + WARN (cap 3 LOCK — ADR 0077 §3-3 정합).
     *
     * I-1: subscribe 성공 시 counter reset — 동일 connection 내 OPSP8996 사이클 분리.
     * - attempt > 1 분기에서 reset(0) 추가 — evidence attempt=69~85 누적 재발 차단.
     * - ADR 0077 §4-3 결함 #2 fix.
     *
     * 재전송 전략: sendSink에 현재 구독 목록(H0UNCNT0) 재전송.
     * OPSP8996은 일시적 appkey 세션 중복 — KIS 서버가 이전 session을 정리한 후
     * 동일 approval_key로 재subscribe하면 대부분 해소됨.
     *
     * KIS-first 근거: approval_key 24h 유효 — 재발급 없이 동일 key 재시도가 KIS 표준 패턴.
     */
    private fun handleOpsp8996(rawJson: String) {
        val attempt = opsp8996RetryCount.incrementAndGet()

        // I-1: cap 3 — KIS 표준 KISWebSocket max_retries=3 정합.
        // KIS-first raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
        // KISWebSocket.__init__(max_retries: int = 3) + retry_count < max_retries 조건.
        if (attempt > 3) {
            log.warn(
                "KIS WS OPSP8996 cap 3 초과 (attempt={}) — connection dispose + scheduleReconnect 위임 " +
                    "(KIS 표준 max_retries=3 정합, ADR 0077 I-1): json={}",
                attempt,
                rawJson.take(200),
            )
            opsp8996RetryCount.set(0) // I-1: reset — 다음 connection 사이클 정합
            wsDisposable?.dispose() // watchdog doFinally → scheduleReconnect 트리거
            return
        }

        if (attempt > 1) {
            // 2회 이상 실패 — ERROR + counter reset 0 + skip (다음 watchdog cycle에 위임).
            // I-1: reset(0) 추가 — attempt 무한 누적 결함 fix (ADR 0077 §4-3 결함 #2).
            opsp8996RetryCount.set(0)
            log.error(
                "KIS WS OPSP8996 2회 fail — counter reset + skip (다음 watchdog cycle 대기): attempt={} json={}",
                attempt,
                rawJson.take(200),
            )
            return
        }
        // 1회: 1초 대기 후 재구독 시도
        log.warn(
            "KIS WS OPSP8996 감지 — 1초 대기 후 retry (attempt={}): json={}",
            attempt,
            rawJson.take(200),
        )
        try {
            Thread.sleep(1000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 현재 활성 구독 목록으로 재구독 메시지 재전송
        val approvalKey = getApprovalKey()
        if (approvalKey == null) {
            log.warn("KIS WS OPSP8996 retry — approval_key 발급 불가, skip")
            opsp8996RetryCount.set(0) // I-1: 발급 불가 시에도 reset — 무한 누적 방지
            return
        }
        val resubscribeCodes = session.subscribedCodes.toList()
        if (resubscribeCodes.isNotEmpty()) {
            resubscribeCodes.forEach { code -> sendTickSubscribe(approvalKey, code, trType = "1") }
            log.info("KIS WS OPSP8996 retry 재구독 전송: {}건 trId={}", resubscribeCodes.size, TR_ID_UNIFIED_TICK)
        } else {
            log.warn("KIS WS OPSP8996 retry — 재구독 목록 없음, skip")
            opsp8996RetryCount.set(0) // I-1: 재구독 없음 시에도 reset
        }
    }

    /**
     * OPSP0008(MAX SUBSCRIBE OVER) 구독 거부 처리 (ADR 0123 §3-4 R1 graceful path).
     *
     * KIS WS 동시 구독 한도(40)를 초과한 신규 subscribe 요청이 거부됐을 때:
     * 1. 거부 응답 header에서 (trId, trKey=code) 추출.
     * 2. **ledger 회계 보정** — 해당 채널 점유 취소 + 거부 카운트 + WARN(1회/주기 throttle).
     * 3. **subscribedCodes 정합** — 현행 결함(거부돼도 subscribedCodes에 남아 재구독·재시도 0)을 정정:
     *    거부된 채널이 H0UNCNT0면 session.subscribedCodes에서, H0UNASP0면 orderbookSubscribedCodes에서
     *    제거해 "구독 중인데 실제 미점유" stale 상태를 해소. 이후 owner 로직이
     *    재시도 가능 상태로 둔다(R2 eviction이 slot 확보 후 재등록).
     * 4. **graceful fallback hook** — onSubscribeRejected(code, trId) 발사 → gap-fill·polling 연결
     *    (분봉 연속성 보장 — 후성 093370 결함 직접 해소). 미등록 시 graceful skip.
     *
     * 헌법 §11-2 정합: silent 금지 — ledger WARN + fallback 명시. REAL 모드라도 시세 누락은 자본 직결 아님
     * (주문 발사 아님) → fail-fast 아닌 명시 degraded(polling/gap-fill) 진행.
     *
     * KIS-first: 거부 응답 format — 2026-06-11 라이브 실측 (ADR 0123 §1-1)
     *   {"header":{"tr_id":"H0UNASP0","tr_key":"093370"},"body":{"rt_cd":"1","msg_cd":"OPSP0008","msg1":"MAX SUBSCRIBE OVER"}}
     */
    private fun handleOpsp0008(rawJson: String) {
        val channel = KisWsMessageParser.extractRejectedChannel(rawJson)
        if (channel == null) {
            log.warn(
                "KIS WS OPSP0008 거부 — (trId, tr_key) 추출 실패, 회계 보정 skip (관측만): json={}",
                rawJson.take(200),
            )
            return
        }
        val (trId, code) = channel
        // 2. ledger 회계 보정 (WARN throttle은 ledger 내부).
        slotLedger()?.onRejected(trId = trId, code = code)

        // 3. subscribedCodes 정합 — 거부된 채널을 구독 set에서 제거 (stale "구독 중" 상태 해소).
        when (trId) {
            TR_ID_UNIFIED_TICK -> session.subscribedCodes.remove(code)
            TR_ID_UNIFIED_ORDERBOOK -> orderbookSubscribedCodes.remove(code)
            TR_ID_UNIFIED_MARKET_STATUS -> marketStatusSubscribedCodes.remove(code)
            "H0STOUP0" -> overtimeSinglePriceCodes.remove(code)
            else -> log.debug("KIS WS OPSP0008 — set 정합 대상 아닌 trId={} (체결통보 등)", trId)
        }

        // 4. graceful fallback hook — gap-fill + polling 연결 (미등록 시 skip).
        try {
            onSubscribeRejected?.invoke(code, trId)
        } catch (e: Exception) {
            log.error(
                "KIS WS OPSP0008 fallback hook 실패 — gap-fill/polling 건너뜀(분봉 연속성 risk): code={} trId={} err={}",
                code,
                trId,
                e.message,
            )
        }
    }

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    /**
     * Graceful shutdown (ADR 0045 — SIGTERM 시 ≤ 5s).
     *
     * E-1: unsubscribe 메시지 send 후 ACK 2s 대기 (ADR 0076 E-1).
     * - unsubscribe 메시지 전송 → ACK(msg_cd "OPSP0002" or msg1 "UNSUB…") 수신 확인 후 close.
     * - 2s 내 ACK 미수신 시 강제 close (graceful + timeout 보장).
     * - KIS 서버에서 ACK 도착 전 연결 종료 시 다음 connect에서 ALREADY IN USE risk 방지.
     *
     * KIS-first 출처 (isUnsubscribeAck 판별 기준):
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     *   isUnSub = True if tr_msg[:5] == "UNSUB" else False
     */
    @PreDestroy
    fun destroy() {
        log.info("KIS WS graceful shutdown 시작")
        session.shuttingDown = true
        stopHeartbeatWatchdog()
        stopNoTickWatchdog()

        // 모든 종목 unsubscribe (H0UNCNT0 체결가 + H0UNASP0/H0UNMKO0/H0STOUP0)
        val codes = session.subscribedCodes.toList()
        val orderbookCodes = orderbookSubscribedCodes.toList()
        val marketStatusCodes = marketStatusSubscribedCodes.toList()
        val overtimeCodes = overtimeSinglePriceCodes.toList()
        val hasSubscriptionsToRelease =
            codes.isNotEmpty() ||
                orderbookCodes.isNotEmpty() ||
                marketStatusCodes.isNotEmpty() ||
                overtimeCodes.isNotEmpty()
        if (hasSubscriptionsToRelease) {
            try {
                val approvalKey = getApprovalKey()
                // G-1: approval_key null 시 unsubscribe 메시지 전송 불가 — WS session 강제 종료로 대체.
                if (approvalKey == null) {
                    log.warn("KIS WS shutdown — approval_key 발급 불가, unsubscribe 메시지 전송 생략 (WS 연결 강제 종료)")
                    wsDisposable?.dispose()
                    sendSink?.tryEmitComplete()
                    scheduler.shutdown()
                    scheduler.awaitTermination(3, TimeUnit.SECONDS)
                    log.info("KIS WS graceful shutdown 완료 (approval_key 없음 — 강제 종료)")
                    return
                }

                // E-1: ACK 대기 latch 설정 — unsubscribe 전송 직전에 활성화
                // 1건 이상 ACK 수신 시 latch.countDown() → awaitAck 해제
                val ackLatch = java.util.concurrent.CountDownLatch(1)
                pendingUnsubAckLatch.set(ackLatch)

                codes.forEach { code ->
                    val msg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_UNSUBSCRIBE, trId = TR_ID_UNIFIED_TICK)
                    sendSink?.tryEmitNext(msg)
                    log.debug("KIS WS shutdown unsubscribe 전송: code={} trId={}", code, TR_ID_UNIFIED_TICK)
                }
                // H0UNASP0 통합 호가 unsubscribe.
                orderbookCodes.forEach { code ->
                    val orderbookMsg =
                        buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_UNSUBSCRIBE, trId = TR_ID_UNIFIED_ORDERBOOK)
                    sendSink?.tryEmitNext(orderbookMsg)
                    log.debug("KIS WS shutdown unsubscribe 전송: code={} trId={}", code, TR_ID_UNIFIED_ORDERBOOK)
                }
                // H0UNMKO0 통합 장운영/VI unsubscribe.
                marketStatusCodes.forEach { code ->
                    val marketStatusMsg =
                        buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_UNSUBSCRIBE, trId = TR_ID_UNIFIED_MARKET_STATUS)
                    sendSink?.tryEmitNext(marketStatusMsg)
                    log.debug("KIS WS shutdown unsubscribe 전송: code={} trId={}", code, TR_ID_UNIFIED_MARKET_STATUS)
                }
                // H0STOUP0 시간외 단일가 unsubscribe — ADR 0076 Phase 3 B-2
                // KIS-first: overtime_ccnl_krx.py tr_type "2" = 구독 해제
                // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/overtime_ccnl_krx/overtime_ccnl_krx.py
                overtimeCodes.forEach { code ->
                    val overtimeMsg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_UNSUBSCRIBE, trId = "H0STOUP0")
                    sendSink?.tryEmitNext(overtimeMsg)
                    log.debug("KIS WS shutdown unsubscribe 전송: code={} trId=H0STOUP0", code)
                }

                // H0STCNI0/H0STCNI9 체결통보 unsubscribe — ADR 0068 §14-6 LOCK
                // KIS-first: ccnl_notice.py tr_type "2" = 구독 해제
                // raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/ccnl_notice/ccnl_notice.py
                unsubscribeExecutionNoticeIfPossible(approvalKey)

                // E-1: ACK 2s 대기 — KIS 서버에서 ACK 도착 전 연결 종료 방지
                // ADR 0076 E-1: 2초 대기 후 ACK 미수신 시에도 강제 close (graceful + timeout)
                val ackReceived = ackLatch.await(2, TimeUnit.SECONDS)
                if (ackReceived) {
                    log.info("KIS WS unsubscribe ACK 수신 — 정상 종료")
                } else {
                    log.warn("KIS WS unsubscribe ACK 2s timeout — 강제 close (ACK 미수신)")
                }
                pendingUnsubAckLatch.set(null)
            } catch (e: Exception) {
                log.warn("KIS WS shutdown 시 unsubscribe 실패: {}", e.message)
                pendingUnsubAckLatch.set(null)
            }
        }

        wsDisposable?.dispose()
        sendSink?.tryEmitComplete()
        scheduler.shutdown()
        scheduler.awaitTermination(3, TimeUnit.SECONDS) // 5s 한도 내 유지 (2s ACK 대기 + 3s)
        log.info("KIS WS graceful shutdown 완료")
    }

    /**
     * KIS WebSocket approval_key 반환.
     *
     * H-1: 시세 WS는 정보 수집 영역 → AccountType.REAL 고정 (magicjarMode 분기 제거).
     * - KIS-first 출처: open-trading-api examples_user/kis_auth.py — 예제 코드는 모두 REAL 환경 기준.
     * - 사용자 메모리 [feedback_kis_key_realonly_for_data]: "시세·캔들·호가 등 정보 수집은 모두 실전 키.
     *   모의/실전 분기는 주문 액션에만."
     * - raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     *
     * G-1: 자격증명 미등록 시 null 반환 (KIS 표준 패턴 정합).
     * - KIS-first 출처 kis_auth.py auth_ws():
     *   "Get Approval token fail!\nYou have to restart your app!!!" + return (None 반환)
     *   → 호출자가 null 체크 후 connect skip하는 것이 KIS 표준.
     * - "ERROR_APPROVAL_KEY" 문자열 fallback 폐기 — silent path 차단.
     * - raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     *
     * @return approval_key 문자열. 자격증명 미등록·user-id 미설정 시 null 반환.
     */
    private fun getApprovalKey(): String? {
        if (defaultUserId.isBlank()) {
            log.warn(
                "KIS WS user-id 미설정 — approval_key 발급 불가 (magicjar.kis.ws.user-id 확인 필요). " +
                    "SetupWizard에서 REAL 자격증명 등록 후 재부팅하세요.",
            )
            return null
        }
        // H-1: 시세 WS는 REAL 고정 — magicjarMode 분기 제거.
        // KIS-first raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
        // KIS 예제는 모두 REAL 환경 기준. 정보 수집 = REAL 자격증명 단일 출처.
        val accountType = AccountType.REAL
        return try {
            approvalAdapter.getApprovalKey(defaultUserId, accountType)
        } catch (e: IllegalStateException) {
            // G-1: 자격증명 미등록 → null 반환 (KIS auth_ws() None 반환 패턴 정합).
            // KIS-first raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
            // auth_ws() 실패 시 print("You have to restart your app!!!") + return (None).
            // 호출자(doConnect/subscribe)가 null 체크 후 connect/subscribe skip.
            log.warn(
                "KIS WS approval_key 발급 불가 — REAL 자격증명 미등록 " +
                    "(SetupWizard에서 REAL KIS 키 등록 후 재부팅 필요 — KIS 표준: You have to restart your app!!!): " +
                    "userId={}",
                defaultUserId.take(4),
            )
            null
        } catch (e: Exception) {
            log.error("KIS WS approval_key 발급 실패: userId={} error={}", defaultUserId.take(4), e.message)
            null
        }
    }

    /**
     * KIS WS endpoint를 거래 환경(magicjar.mode)에 정합시킨다 — REAL=prod(:21000) / MOCK·PAPER=vps(:31000).
     *
     * ### 2026-06-10 P1 자본 path 회귀 fix (체결통보 H0STCNI9 6/8 수신 0 — 라이브 실측 확정)
     * 직전(e0a88a3f, 2026-06-06) `getWsUrl()=wsUrlProd` 무조건 고정이 회귀 근본 원인이었다.
     *
     * - 체결통보 tr_id는 [executionNoticeTrId]가 MOCK 모드에서 H0STCNI9(모의 체결통보)를 발사한다.
     * - 모의 체결통보(H0STCNI9)는 **모의 WS endpoint(vops:31000)에서만 발송**된다.
     *   prod endpoint(:21000)에 H0STCNI9를 구독하면 KIS는 형식 검증만 통과시켜 SUBSCRIBE SUCCESS를
     *   주지만(라이브 실측 확인), 그 사용자의 모의 계좌 체결은 prod 서버가 모르므로 데이터를 영구 미발송한다.
     * - 시세(H0STCNT0)는 prod·vops 동일 발송이라 캔들은 FRESH였고(회귀 은폐), 자본 path인 체결통보만 끊겼다.
     *   → 6/5까지 MOCK→vops 연결에서 H0STCNI9 정상 수신 → 6/6 endpoint 고정 후 재배포(6/8)부터 WS 체결통보 0.
     *
     * ### KIS-first 출처 (endpoint↔환경 정합 의무)
     * - kis_auth.py auth_ws(svr): `_cfg["ops" if svr=="prod" else "vops"]` — approval_key·WS endpoint가
     *   동일 svr(prod/vps)에서 파생되어야 한다. 환경 mix 금지(prod approval_key ↔ vops endpoint 불가).
     *   raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
     *
     * ### endpoint↔tr_id 정합 LOCK
     * 본 분기와 [executionNoticeTrId]는 동일 기준([RuntimeModeParser.isRealMode])을 쓴다 →
     *   MOCK → vops + H0STCNI9 / REAL → prod + H0STCNI0 으로 항상 짝이 맞는다.
     */
    private fun getWsUrl(): String = if (RuntimeModeParser.isRealMode(magicjarMode)) wsUrlProd else wsUrlVps

    /**
     * KIS WS subscribe/unsubscribe 메시지 빌드.
     *
     * KIS-first 출처: open-trading-api kis_auth.py data_fetch() + dispatch-spec §4-4:
     * {
     *   "header": {"approval_key": "...", "tr_type": "1", "custtype": "P"},
     *   "body": {"input": {"tr_id": "H0UNCNT0", "tr_key": "005930"}}
     * }
     */
    private fun buildSubscribeMessage(
        approvalKey: String,
        code: String,
        trType: String, // "1" = subscribe, "2" = unsubscribe
        trId: String = TR_ID_UNIFIED_TICK, // 기본: H0UNCNT0 통합 체결가
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "header" to
                    mapOf(
                        "approval_key" to approvalKey,
                        "tr_type" to trType,
                        "custtype" to "P",
                        "content-type" to "utf-8",
                    ),
                "body" to
                    mapOf(
                        "input" to
                            mapOf(
                                "tr_id" to trId,
                                "tr_key" to code,
                            ),
                    ),
            ),
        )

    /**
     * unsubscribe 메시지 sink emit + 전송 원문 로깅 + 이력 기록 (결함③ 진단 관측 — 부작용 0).
     *
     * 동작은 `sendSink?.tryEmitNext(buildSubscribeMessage(... trType=TR_TYPE_UNSUBSCRIBE ...))`와 1:1 동일하다.
     * 추가되는 것은:
     *  1. **전송 원문(JSON) 로깅** — sink emit 직전. 다음 OPSP8997 발생 시 그 wire JSON 이 정상인지/깨졌는지
     *     (동시 emit race로 corruption 등) 눈으로 확정 가능.
     *  2. **이력 기록** ([recentUnsubscribes]) — OPSP8997 응답은 header `tr_id=null`이라 추적 불가하므로,
     *     직전 발사 해제 메시지를 상관관계 덤프([dumpRecentUnsubscribesForDiagnosis])로 남긴다.
     *
     * @return tryEmitNext 결과 — 기존 호출부가 결과를 쓰지 않으므로 호환(반환만 추가).
     */
    private fun emitUnsubscribeMessage(
        approvalKey: String,
        code: String,
        trId: String,
    ) {
        val msg = buildSubscribeMessage(approvalKey, code, trType = TR_TYPE_UNSUBSCRIBE, trId = trId)
        // 전송 원문 로깅 — emit 직전(헌법 §11-2 silent 금지). OPSP8997 진단 시 wire 정상 여부 확정 근거.
        log.info("KIS WS unsubscribe 전송 원문: code={} trId={} trType={} raw={}", code, trId, TR_TYPE_UNSUBSCRIBE, msg)
        recordUnsubscribeTrace(code = code, trId = trId, rawJson = msg)
        sendSink?.tryEmitNext(msg)
    }

    /** 최근 unsubscribe 이력 push + 상한 trim (결함③ 진단 — [recentUnsubscribes]). */
    private fun recordUnsubscribeTrace(
        code: String,
        trId: String,
        rawJson: String,
    ) {
        recentUnsubscribes.addLast(
            UnsubscribeTrace(
                epochMs = System.currentTimeMillis(),
                code = code,
                trId = trId,
                rawJson = rawJson,
            ),
        )
        // 상한 trim — 관측 전용이므로 race로 1~2건 오차 무해.
        while (recentUnsubscribes.size > UNSUB_HISTORY_MAX) {
            recentUnsubscribes.pollFirst() ?: break
        }
    }

    /**
     * OPSP8997 invalid tr_type 수신 시 최근 unsubscribe 이력 덤프 (결함③ 진단 상관관계).
     *
     * OPSP8997 응답은 header `tr_id=null`이라 어떤 해제가 거부됐는지 응답만으로는 추적 불가하다.
     * 직전 [UNSUB_HISTORY_MAX]건 해제 발사 이력(시각·code·trId·전송 원문 JSON)을 함께 찍어
     * 운영자가 "거부 직전 어떤 메시지가 나갔고 그 JSON 이 정상인지"를 눈으로 잇게 한다.
     */
    private fun dumpRecentUnsubscribesForDiagnosis(invalidTrTypeJson: String) {
        val recent = recentUnsubscribes.toList()
        log.warn(
            "KIS WS OPSP8997 invalid tr_type 수신 — 응답 tr_id=null로 추적 불가. 거부 응답 원문(full)={} | 최근 unsubscribe 발사 {}건 상관관계 덤프 시작",
            invalidTrTypeJson,
            recent.size,
        )
        val now = System.currentTimeMillis()
        recent.takeLast(UNSUB_HISTORY_MAX).forEach { trace ->
            log.warn(
                "KIS WS OPSP8997 상관관계 — {}ms 전 발사: code={} trId={} raw={}",
                now - trace.epochMs,
                trace.code,
                trace.trId,
                trace.rawJson,
            )
        }
    }

    /** 최근 unsubscribe 전송 이력 1건 (결함③ 진단 관측 전용). */
    private data class UnsubscribeTrace(
        val epochMs: Long,
        val code: String,
        val trId: String,
        val rawJson: String,
    )

    /**
     * hotfix 2026-05-20 — KIS WS 구독 SUCCESS 응답에서 AES iv·key 추출 + [trIdToAesKey] 저장.
     *
     * KIS 공식 응답 format (subscribe SUCCESS):
     * ```
     * {"header":{"tr_id":"H0STCNI9","tr_key":"...","encrypt":"N"},
     *  "body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS",
     *          "output":{"iv":"42e0241e013374b7","key":"ygnh..."}}}
     * ```
     *
     * iv·key 부재 시 (체결가 등 비암호화 구독) skip — log 0.
     * iv·key 존재 시 trIdToAesKey[trId] 저장 → 후속 RealtimeData(encrypted=true) 도착 시 복호화.
     */
    private fun extractAndStoreAesKey(rawJson: String) {
        runCatching {
            val root = objectMapper.readTree(rawJson)
            val trId =
                root
                    .path("header")
                    .path("tr_id")
                    .asText("")
                    .takeIf { it.isNotBlank() } ?: return@runCatching
            val output = root.path("body").path("output")
            val iv = output.path("iv").asText("").takeIf { it.isNotBlank() } ?: return@runCatching
            val key = output.path("key").asText("").takeIf { it.isNotBlank() } ?: return@runCatching
            trIdToAesKey[trId] = KisWsAesKey(iv = iv, key = key)
            log.info("KIS WS AES key 저장: trId={} ivLen={} keyLen={}", trId, iv.length, key.length)
        }.onFailure { e ->
            log.debug("KIS WS AES key 추출 skip: err={}", e.message)
        }
    }

    /**
     * WS_DISCONNECTED 알림 강등 지연 평가 (2026-06-04 점검 사이클 #4).
     *
     * disconnect 발생 직후 즉시 WARN을 발사하면 매시 정각 KIS 서버 onComplete(0~2초 내 자동 회복)가
     * 매번 P2 Slack 알림으로 노이즈화된다. grace window 후 재연결 여부를 재평가해 severity를 분기한다:
     *   - session.connected=true (회복) → INFO emit → dispatcher priorityOf(INFO)=null → Slack 0 (audit·UI만)
     *   - 미회복 → WARN emit → dispatcher P2 → 정상 알림 (S-2 SLO 위반 감시)
     *
     * S-2 KIS WS 연결률 SLO 관측 보존: 어느 분기든 WS_DISCONNECTED 사건은 반드시 1회 emit한다.
     * severity만 조건화 — metric/이벤트 기록 자체는 제거하지 않는다 (디스패치 명세 제약).
     *
     * correlationId 그룹화 유지: emit point #5(onConnected)에서 동일 cid를 회수하면 INFO든 WARN이든
     * disconnect↔reconnect 1쌍 audit이 정합하게 닫힌다.
     *
     * scheduler 사용 사유: doFinally는 Reactor thread 위 — Thread.sleep 블록 회피.
     * shuttingDown 진입 시 평가 skip (graceful shutdown 중 disconnect는 사고 아님).
     */
    private fun scheduleDisconnectAlert(
        correlationId: String,
        signal: String,
    ) {
        val graceMs = disconnectAlertGraceMs
        scheduler.schedule({
            if (session.shuttingDown) return@schedule
            val recovered = session.connected
            val severity = if (recovered) SystemEventSeverity.INFO else SystemEventSeverity.WARN
            emitWsEvent(
                severity = severity,
                action = "WS_DISCONNECTED",
                correlationId = correlationId,
                detailJson =
                    """{"reason":"$signal","recoveredWithinGrace":$recovered,"graceMs":$graceMs}""",
            )
            if (recovered) {
                log.info(
                    "KIS WS disconnect — grace {}ms 내 재연결 회복 → INFO 강등 (Slack 노이즈 억제): cid={}",
                    graceMs,
                    correlationId,
                )
            } else {
                log.warn(
                    "KIS WS disconnect — grace {}ms 후 미회복 → WARN 발사 (S-2 SLO 감시): cid={}",
                    graceMs,
                    correlationId,
                )
            }
        }, graceMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    /**
     * WS_RECONNECT_LOST 오탐 정정 — 이중 단절(onComplete 직후 onError) 회복 재평가 (2026-06-04 점검 사이클 #5).
     *
     * ### 배경 (운영 evidence)
     * 매시 정각 KIS 서버는 onComplete로 연결을 끊지만, 드물게 onComplete 직후 onError가 연달아 와
     * doFinally가 2번 진입한다(이중 단절). 두 번째 진입은 첫 번째 disconnect cid를 stale로 감지하고
     * 기존엔 즉시 WARN `WS_RECONNECT_LOST`를 발사했다. 그러나 실측(18:00·22:00)에서 동일 시각 재구독은
     * 1~2초 내 성공("재구독 완료: 종목 10건")한다 — 즉 회복됐는데 WARN이 남아 Slack 노이즈가 재발했다.
     *
     * ### fix
     * WS_DISCONNECTED 강등([scheduleDisconnectAlert])과 동일하게 grace window 후 재평가:
     *   - grace 내 재연결 성공(session.connected=true) → INFO emit → dispatcher priorityOf(INFO)=null → Slack 0.
     *     즉 이중 단절이지만 재구독 성공으로 회복됐으므로 LOST 판정을 취소(INFO로 강등)한다.
     *   - grace 후에도 미회복(session.connected=false) → WARN emit (진짜 reconnect 유실 — S-2 SLO 감시 보존).
     *
     * grace 판정 신호 = session.connected. onConnected() 진입(=재구독 발사 직전)에서 true로 전환되므로,
     * 재구독 완료 이벤트를 grace 판정의 회복 신호로 정확히 연결한다.
     *
     * correlationId 그룹화 유지: emit point #5(onConnected)에서 새 cid를 회수하므로, 이전(stale) cid에 대한
     * 본 emit은 INFO든 WARN이든 해당 disconnect↔reconnect 1쌍 audit을 닫는다.
     *
     * shuttingDown 진입 시 평가 skip (graceful shutdown 중 이중 단절은 사고 아님).
     */
    private fun scheduleReconnectLostAlert(
        stalePrevCid: String,
        signal: String,
    ) {
        val graceMs = disconnectAlertGraceMs
        scheduler.schedule({
            if (session.shuttingDown) return@schedule
            val recovered = session.connected
            val severity = if (recovered) SystemEventSeverity.INFO else SystemEventSeverity.WARN
            emitWsEvent(
                severity = severity,
                action = "WS_RECONNECT_LOST",
                correlationId = stalePrevCid,
                detailJson =
                    """{"reason":"doFinally 중복 발생 — 이전 cid stale (onComplete+onError 연속 패턴)",""" +
                        """"signal":"$signal","recoveredWithinGrace":$recovered,"graceMs":$graceMs}""",
            )
            if (recovered) {
                log.info(
                    "KIS WS 이중 단절 — grace {}ms 내 재구독 회복 → WS_RECONNECT_LOST INFO 강등 (오탐 정정, Slack 노이즈 0): prevCid={}",
                    graceMs,
                    stalePrevCid,
                )
            } else {
                log.warn(
                    "KIS WS 이중 단절 — grace {}ms 후 미회복 → WS_RECONNECT_LOST WARN 발사 (S-2 SLO 감시): prevCid={}",
                    graceMs,
                    stalePrevCid,
                )
            }
        }, graceMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    }

    /**
     * SystemEvent emit graceful wrap — WS catalog (spec §6.1 emit point #4·#5).
     *
     * runCatching 의무 — emit 실패가 WS doFinally cascade 차단 방지 (재연결 흐름 보호).
     * SystemEventLoggerAdapter 자체 graceful이지만, port 계약 따라 caller 측 추가 wrap (헌법 §6).
     *
     * userId=null (broadcast) — KIS WS는 단일 시스템 연결 (multi-user 분기 없음).
     */
    private fun emitWsEvent(
        severity: SystemEventSeverity,
        action: String,
        correlationId: String,
        detailJson: String,
    ) {
        runCatching {
            systemEventLogger.emit(
                SystemEvent(
                    category = SystemEventCategory.WEBSOCKET,
                    severity = severity,
                    actor = SYSTEM_EVENT_ACTOR,
                    action = action,
                    userId = null,
                    correlationId = correlationId,
                    detailJson = detailJson,
                ),
            )
        }.onFailure { e ->
            log.error("SystemEventLogger.emit 실패: action={} err={}", action, e.message)
        }
    }
}

/**
 * 관리자 실시간 상태 스냅샷용 WS 세션·체결통보 투영 (read-only 진단).
 *
 * @param connected 현재 세션 연결 여부.
 * @param sessionCid 살아있는 세션 UUID(끊김 시 null).
 * @param reconnects24h 최근 24h 재연결 횟수.
 * @param lastConnectedAtMs 마지막 연결 수립 시각(epoch ms, 미연결 이력 시 null).
 * @param executionSubscribed 체결통보(H0STCNI0/9) 자동 구독 활성 여부.
 * @param executionMode 운영 모드(REAL/MOCK — tr_id 분기 source).
 * @param executionTrId 현재 체결통보 tr_id(REAL=H0STCNI0 / MOCK=H0STCNI9).
 * @param executionUserIdMasked 체결통보 구독 HTS 사용자 ID(마스킹, 미설정 시 null).
 */
data class WsStatusSnapshot(
    val connected: Boolean,
    val sessionCid: String?,
    val reconnects24h: Int,
    val lastConnectedAtMs: Long?,
    val executionSubscribed: Boolean,
    val executionMode: String,
    val executionTrId: String,
    val executionUserIdMasked: String?,
)
