package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Watchlist Ingestion Worker — facade (ADR 0028 §2-1 · ADR 0124 결정 2 정합).
 *
 * 역할:
 * - @PostConstruct: 부팅 reconcile (watchlist 전체 조회 → SubscriptionRouter.recompute = polling pool 등록).
 *   ★ ADR 0124: watchlist는 WS 구독 0(5초 REST polling 전용). KIS WS connect는 owner demand
 *   (STRATEGY/HOLDING/PENDING_BUY/DETAIL) 재구독 핸드셰이크용으로만 트리거한다.
 * - @Scheduled 1분 cron: MarketSession 변경 감지 → onSessionChanged 호출 (polling start/stop)
 * - @PreDestroy: graceful shutdown (KIS WS unsubscribe 전체 + polling stop — ADR 0045)
 */
@Component
class WatchlistIngestionWorker(
    private val subscriptionRouter: SubscriptionRouter,
    private val pollingWorker: WatchlistPollingWorker,
    private val kisWsPort: KisWsPort,
    private val watchlistRepository: ConsumerWatchlistRepositoryAdapter,
    private val marketSessionResolver: MarketSessionResolver,
    /** J-1: 부팅 사전 검증 — REAL 자격증명 존재 확인 후 KIS WS connect 진입 (ADR 0077 J-1). */
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
) {
    private val log = LoggerFactory.getLogger(WatchlistIngestionWorker::class.java)

    @Volatile
    private var lastSession: MarketSession = MarketSession.Closed(reason = "init")

    /**
     * 부팅 reconcile — watchlist polling pool 등록 + KIS WS 연결 (ADR 0028 §4-5 · ADR 0124 결정 2).
     *
     * 순서:
     *   1. watchlist 조회 → 사용자 추출
     *   2. SubscriptionRouter.recompute() — watchlist 전 종목을 polling pool에 등록(WS 구독 0 — ADR 0124).
     *   3. kisWsPort.connect() — WS 연결 시작 (approval_key 발급 + 핸드셰이크).
     *      owner demand(STRATEGY/HOLDING/PENDING_BUY/DETAIL)는 각 registrar가 부팅 후 보강하며,
     *      handshake 완료 시 onConnected가 session.subscribedCodes(owner demand 구독)를 단일 권위로 재구독한다.
     *   4. 세션 확인 → polling worker 상태 결정.
     *
     * ★ ADR 0124 결정 2: watchlist는 더 이상 WS slot을 점유하지 않으므로 과거 watchlist 1m stale race
     *   (connect↔recompute 순서)는 소멸했다. WS 재구독 권위는 owner demand 구독 set이며, connect는 그 owner
     *   demand 핸드셰이크를 위해 선행 트리거한다.
     *
     * ADR 0048 §2-4: connect 실패 시 Bridge 내부에서 지수 백오프 재시도.
     */
    @PostConstruct
    fun onStartup() {
        log.info("WatchlistIngestionWorker 부팅 reconcile 시작")
        try {
            val allEntries = watchlistRepository.findAll()
            val users = allEntries.map { it.userId }.distinct()
            if (users.isEmpty()) {
                log.info("watchlist 없음 — reconcile 생략")
                return
            }

            // J-1: 자격증명 사전 검증 — REAL KIS 키 미등록 시 connect skip + WARN 안내.
            // ADR 0077 J-1: onStartup 진입 시 REAL 자격증명 존재 확인 후 connect.
            // 미등록 시 KIS WS connect 자체를 차단 → ERROR_APPROVAL_KEY → OPSP8996 무한 루프 방지.
            // 사용자 메모리 [feedback_kis_key_realonly_for_data]: "정보 수집=실전 키".
            val realCredentials = credentialRepository.findAllByAccountType(AccountType.REAL.name)
            if (realCredentials.isEmpty()) {
                log.warn(
                    "WatchlistIngestionWorker: REAL KIS 자격증명 미등록 — KIS WS connect skip. " +
                        "SetupWizard(⚙️ SettingsModal)에서 REAL KIS 앱키·시크릿 등록 후 재부팅 필요. " +
                        "watchlist={}건 users={}",
                    allEntries.size,
                    users,
                )
                return
            }

            // watchlist 전 종목을 polling pool에 등록(WS 구독 0 — ADR 0124 결정 2).
            users.forEach { userId ->
                subscriptionRouter.recompute(userId)
            }
            // KIS WS 연결 시작 — owner demand(전략·보유 등) 재구독 핸드셰이크 트리거.
            // onConnected가 owner demand 구독 set(session.subscribedCodes)을 단일 권위로 재구독한다.
            kisWsPort.connect()
            log.info("KIS WS connect() 호출 완료 — 핸드셰이크 진행 중 (등록 종목 onConnected 재구독 위임)")
            // 현재 세션 확인 후 polling worker 상태 결정
            val now = Clock.System.now()
            lastSession = marketSessionResolver.resolve(now)
            if (lastSession is MarketSession.Regular) {
                pollingWorker.start()
            }
            log.info(
                "WatchlistIngestionWorker 부팅 완료: users={}건 session={}",
                users.size,
                lastSession::class.simpleName,
            )
        } catch (e: Exception) {
            log.error("WatchlistIngestionWorker 부팅 reconcile 실패: {}", e.message, e)
        }
    }

    /**
     * 1분 주기 세션 변경 감지 (ADR 0048 §2-5).
     */
    @Scheduled(cron = "0 * * * * ?")
    fun checkSessionChange() {
        val now = Clock.System.now()
        val currentSession = marketSessionResolver.resolve(now)
        if (currentSession::class != lastSession::class) {
            log.info("MarketSession 변경 감지: {} → {}", lastSession::class.simpleName, currentSession::class.simpleName)
            subscriptionRouter.onSessionChanged(lastSession, currentSession)
            lastSession = currentSession
        }
    }

    /**
     * Graceful shutdown — SIGTERM 시 KIS WS unsubscribe 전체 + polling stop (ADR 0045).
     */
    @PreDestroy
    fun onShutdown() {
        log.info("WatchlistIngestionWorker graceful shutdown 시작")
        try {
            pollingWorker.stop()
            val allCodes = kisWsPort.subscribedCodes().toList()
            if (allCodes.isNotEmpty()) {
                kisWsPort.unsubscribe(allCodes)
                log.info("KIS WS 전체 unsubscribe: {}건", allCodes.size)
            }
            // shutdown 완료 대기 (최대 5초 — ADR 0045)
            Thread.sleep(500)
            log.info("WatchlistIngestionWorker graceful shutdown 완료")
        } catch (e: Exception) {
            log.warn("WatchlistIngestionWorker shutdown 중 오류: {}", e.message)
        }
    }
}
