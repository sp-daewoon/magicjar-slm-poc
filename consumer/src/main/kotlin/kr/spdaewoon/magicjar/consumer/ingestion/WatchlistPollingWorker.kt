package kr.spdaewoon.magicjar.consumer.ingestion

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Watchlist polling worker — **시세 자동 polling 폐지** (ADR 0124 amend · 2026-06-15 최고 관리자 결재).
 *
 * ## 2026-06-15 — watchlist 시세 5초 REST polling 제거 (ADR 0124 amend)
 * 기존(ADR 0124 §2-B): watchlist 종목 시세를 5초 주기 KIS REST(`FHKST01010100` inquire-price)로 polling 해
 * `quote:last` Redis 캐시를 채웠다. **운영 실측 결과 이 polling 이 KIS `EGW00201`(초당 거래건수 초과)를
 * 지속 유발하고 USER bucket(주문·잔고 hot path)과 경합해 잔고조회 `EGW00215`까지 밀었다.**
 *
 * 영향도 audit (2026-06-15):
 * - `quote:last` Redis 캐시에 쓰는 라이브 경로는 **본 worker 의 polling 단 하나**였다(WS tick 은 Kafka
 *   `market.tick` → STOMP forward 로 frontend 에 직접 흐르며 Redis `quote:last` 를 채우지 않는다).
 * - watchlist 전용 종목(STRATEGY/HOLDING/PENDING_BUY/DETAIL owner 에 안 걸린 종목)의 화면 시세만
 *   추가로 채우던 경로 — **최고 관리자가 watchlist 시세 화면을 사용하지 않으므로 순수 KIS quota 낭비**로 결재됨.
 * - STRATEGY/DETAIL 종목 상세 화면: frontend 는 STOMP `topic.market.tick` 으로 WS tick 을 실시간 수신하므로
 *   `quote:last` 부재의 영향이 없다. REST 첫 조회(`SymbolMetaService`)는 `quote:last` 1순위가 비면
 *   KIS PriceMetrics(2순위)로 폴백한다(폴백 설계됨 — 회귀 아님).
 *
 * → 본 worker 는 시세 자동 조회 책임을 폐지한다. **watchlist 등록 기능(등록/삭제/priority/DB·pool)은 유지**한다
 *   (향후 정책 변경 시 polling 재활성화 hook 보존). pool/start/stop API 는 호출처 호환 위해 유지하나
 *   더 이상 KIS REST 를 호출하지 않는다.
 *
 * ## 호환 (호출처 무변경)
 * - [SubscriptionRouter] add/remove/recompute → pool 등록/제거 (시세 조회 X).
 * - [WatchlistIngestionWorker] start/stop → 상태 플래그만 (스케줄 tick 없음).
 * - [KisWsRejectFallbackWiring] add → pool 등록만 (WS reject 시 분봉 gap-fill 은 그대로 동작, 시세 polling 은 폐지).
 */
@Component
class WatchlistPollingWorker {
    private val log = LoggerFactory.getLogger(WatchlistPollingWorker::class.java)

    /** thread-safe watchlist pool — 시세 polling 폐지(ADR 0124 amend) 후에도 등록 추적용으로 유지. */
    private val pool: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var _running: Boolean = false
    val running: Boolean get() = _running

    fun add(code: String) {
        pool.add(code)
        log.debug("watchlist pool add: code={} poolSize={} (시세 polling 폐지 — ADR 0124 amend)", code, pool.size)
    }

    fun remove(code: String) {
        pool.remove(code)
        log.debug("watchlist pool remove: code={} poolSize={}", code, pool.size)
    }

    fun start() {
        _running = true
        log.info("WatchlistPollingWorker 시작(상태 플래그만 — 시세 자동 polling 폐지, ADR 0124 amend): poolSize={}", pool.size)
    }

    fun stop() {
        _running = false
        log.info("WatchlistPollingWorker 중단")
    }

    fun poolSize(): Int = pool.size

    fun poolCodes(): Set<String> = pool.toSet()
}
