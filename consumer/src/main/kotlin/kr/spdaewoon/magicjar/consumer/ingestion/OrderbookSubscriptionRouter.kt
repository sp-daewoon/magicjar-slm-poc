package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 호가 구독 라우터 — KIS WS H0UNASP0 통합 호가 (F11 owner 차원).
 *
 * ## owner 분리 — 두 경로 (2026-06-12 재결정 — anchor `detail-orderbook-live-only`)
 * 호가(H0UNASP0) 구독은 다음 **두 owner**에서만 수립된다(보기용 합집합 잠식 차단):
 * - **STRATEGY** — 전략 ACTIVE 시 평가/진입 대상 종목(`onStrategyActivated`). marketable limit 진입가 산정에
 *   ask1/bid1이 필요하므로 호가 WS가 공급돼야 한다. registrar TTL refresh로 갱신, 명시 release로 해제.
 *   동일 종목 여러 전략(ownerId)이 요구하면 합집합으로 1개 구독 공유.
 * - **DETAIL** — 사용자가 종목 상세 화면을 **표시 중인 동안만**(`onSymbolDetailEntered`/`onSymbolDetailExited`).
 *   수동매매 시 호가 없이 주문하면 안 되므로 상세 진입 시 호가를 공급한다. **진입 시 구독 / 이탈 즉시 해제** —
 *   현재 보는 종목만 슬롯을 점유한다. 옛 LRU 10 slot·200ms debounce·5분 idle 잔류 방식은 폐기됐다(보기용 호가가
 *   KIS WS 40 slot 예산을 누적 잠식하던 구조 제거). DETAIL은 ownerId 추적 없이 boolean demand(상세는 동시에 한
 *   종목만 표시 — exit 즉시 회수).
 *
 * 한 종목에 DETAIL·STRATEGY demand가 공존하면 **단 1개 H0UNASP0 구독**으로 둘 다 충족하고(F11 패턴),
 * 모든 owner demand가 빠질 때만 unsubscribe(owner 단위 추적 — 합집합으로 섞이지 않게).
 *
 * ## 배경 (F11 — 2026-06-12 라이브 incident + 표시-중 복원)
 * 전략 ACTIVE 경로의 호가 구독이 전무해 라이브(14:48)에서 전략 86(후성 093370) BUY가 ask1/bid1/quoteAsOf
 * 전부 null → 전량 `SIGNAL_SKIPPED_NO_QUOTE`. STRATEGY owner 도입(PR #935)으로 전략 매매 경로에 호가를 공급한다.
 * DETAIL 경로는 #939에서 전면 제거됐다가, 수동매매 시 호가 필요성으로 **표시 중에만** 방식으로 부분 복원됐다
 * (PR — anchor `detail-orderbook-live-only`).
 *
 * ## DETAIL idle 안전망 (브라우저 강제 종료 대비)
 * 정상 흐름은 페이지 이탈 시 EXIT 명령이 와 즉시 해제하지만, 브라우저 강제 종료·네트워크 단절로 EXIT가 누락될 수 있다.
 * 이를 대비해 DETAIL 호가 demand에 한해 [DETAIL_IDLE_TIMEOUT_MS](15분) idle 안전망을 둔다. enter 시 lastSeenMs를
 * 갱신하므로 표시 중인 종목은 안전망에 걸리지 않는다. STRATEGY demand는 idle 대상 아님(registrar TTL이 관리 —
 * 진입가 호가 공백 = 자본 risk).
 *
 * ## WS 슬롯 회계 (ADR 0123)
 * subscribe/unsubscribe 발사 시 [WsSlotBudgetLedger]에 (H0UNASP0, code) 채널을 register/release한다
 * (체결가·NXT·체결통보와 단일 회계 — 40 한도 총 점유 가시화). DETAIL은 표시 중 1종목만 점유하므로 LRU 예산 불필요.
 *
 * Thread safety: ReentrantLock.
 */
@Component
class OrderbookSubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val wsSlotBudgetLedger: WsSlotBudgetLedger,
    // DETAIL 호가 idle 안전망 timeout(ms). default 15분(EXIT 누락 보완).
    // 테스트는 짧은 값을 주입해 안전망 동작을 검증한다(운영 흐름엔 노출되는 설정 키 없음 — 상수 default).
    private val detailIdleTimeoutMs: Long = DETAIL_IDLE_TIMEOUT_MS,
) {
    private val log = LoggerFactory.getLogger(OrderbookSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    /** H0UNASP0 tr_id — WsSlotBudgetLedger 채널 회계 key (KIS-first: 통합 호가 실시간). */
    private val trId = TR_ID_UNIFIED_ORDERBOOK

    // STRATEGY demand: code → ownerId 집합 (전략 여러 개가 같은 종목을 요구할 수 있음 — 합집합).
    private val strategyDemands: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // DETAIL demand: code → lastSeenMs (사용자가 상세 표시 중인 종목 — exit 즉시 회수, idle 안전망용 마지막 enter 시각).
    private val detailDemands: MutableMap<String, Long> = mutableMapOf()

    // 관리자 스냅샷용 — code별 최초 호가 구독 시각(epoch ms). 모든 owner가 빠지면 제거.
    private val subscribedSinceMs: MutableMap<String, Long> = mutableMapOf()

    /**
     * 종목이 어느 owner로든(DETAIL ∪ STRATEGY) 호가 구독을 필요로 하는지.
     * 호출자가 lock 보유 전제(Locked 접미사).
     */
    private fun isDemandedLocked(code: String): Boolean = detailDemands.containsKey(code) || strategyDemands[code]?.isNotEmpty() == true

    /**
     * 실제 WS subscribe 발사 — 아직 미구독이면 1회. ledger register 동반.
     * 호출자가 lock 보유 전제.
     */
    private fun ensureSubscribedLocked(code: String) {
        if (kisWsPort.subscribedOrderbookCodes().contains(code)) return
        kisWsPort.subscribeOrderbook(listOf(code))
        wsSlotBudgetLedger.register(trId, code)
    }

    /**
     * owner demand가 모두 빠졌을 때만 실제 WS unsubscribe 발사 — ledger release 동반.
     * 호출자가 lock 보유 전제.
     */
    private fun unsubscribeIfUnownedLocked(code: String) {
        if (isDemandedLocked(code)) return
        if (kisWsPort.subscribedOrderbookCodes().contains(code)) {
            kisWsPort.unsubscribeOrderbook(listOf(code))
            wsSlotBudgetLedger.release(trId, code)
        }
    }

    // ── DETAIL owner (사용자 종목 상세 표시 중) ───────────────────────────────

    /**
     * 사용자가 종목 상세 화면 진입 — DETAIL owner 호가 sub (표시 중에만).
     *
     * 진입 즉시 구독한다. LRU·debounce 없음(현재 보는 종목만 점유 — exit 즉시 회수). lastSeenMs를 갱신해
     * idle 안전망([evictIdleDetailSubscriptions])이 표시 중 종목을 끊지 않게 한다. 동일 종목 STRATEGY demand가
     * 이미 있으면 단일 구독을 공유한다.
     *
     * @param code 종목코드(6자리).
     */
    fun onSymbolDetailEntered(code: String) {
        lock.withLock {
            val now = System.currentTimeMillis()
            val firstEnter = !detailDemands.containsKey(code)
            detailDemands[code] = now
            subscribedSinceMs.putIfAbsent(code, now)
            ensureSubscribedLocked(code)
            if (firstEnter) {
                log.info("orderbook DETAIL subscribe: code={} detailCount={}", code, detailDemands.size)
            } else {
                log.debug("orderbook DETAIL 재진입(lastSeen 갱신): code={}", code)
            }
        }
    }

    /**
     * 사용자가 종목 상세 화면 이탈 — DETAIL owner **즉시** 회수.
     * STRATEGY demand가 남아 있으면 WS는 유지된다(F11 공존).
     */
    fun onSymbolDetailExited(code: String) {
        lock.withLock {
            if (detailDemands.remove(code) == null) return
            if (!isDemandedLocked(code)) subscribedSinceMs.remove(code)
            log.info("orderbook DETAIL release: code={} detailCount={}", code, detailDemands.size)
            unsubscribeIfUnownedLocked(code)
        }
    }

    // ── STRATEGY owner (전략 ACTIVE 평가/진입 대상) ──────────────────────────

    /**
     * 전략 ACTIVE — 평가/진입 대상 종목 호가 sub (STRATEGY owner).
     *
     * marketable limit 진입가([EntryQuotePort])가 ask1/bid1을 요구하므로 호가 WS가 공급돼야 한다.
     * debounce/LRU/idle evict 없음(전략 종목은 페이지 전환 무관 — registrar TTL refresh가 갱신,
     * 명시 release로만 해제). 동일 종목 여러 전략(ownerId)이 요구하면 합집합으로 1개 구독 공유.
     *
     * @param code 종목코드(6자리).
     * @param ownerId 전략 식별자(`strategy:<id>`) — 멱등 register + 다중 전략 합집합.
     */
    fun onStrategyActivated(
        code: String,
        ownerId: String,
    ) {
        lock.withLock {
            val owners = strategyDemands.getOrPut(code) { mutableSetOf() }
            val added = owners.add(ownerId)
            subscribedSinceMs.putIfAbsent(code, System.currentTimeMillis())
            ensureSubscribedLocked(code)
            if (added) {
                log.info(
                    "orderbook STRATEGY subscribe: code={} ownerId={} strategyOwners={}",
                    code,
                    ownerId,
                    owners.size,
                )
            } else {
                log.debug("orderbook STRATEGY 재요청(멱등): code={} ownerId={}", code, ownerId)
            }
        }
    }

    /**
     * 전략 비활성/대상 종목 제외 — STRATEGY owner 회수.
     * 해당 전략 demand만 제거하고, 다른 전략·DETAIL demand가 남으면 WS 유지.
     */
    fun onStrategyDeactivated(
        code: String,
        ownerId: String,
    ) {
        lock.withLock {
            val owners = strategyDemands[code] ?: return
            if (!owners.remove(ownerId)) return
            if (owners.isEmpty()) {
                strategyDemands.remove(code)
                if (!isDemandedLocked(code)) subscribedSinceMs.remove(code)
            }
            log.info(
                "orderbook STRATEGY release: code={} ownerId={} remainingStrategyOwners={}",
                code,
                ownerId,
                owners.size,
            )
            unsubscribeIfUnownedLocked(code)
        }
    }

    /**
     * DETAIL 호가 idle 안전망 — @Scheduled 1분마다.
     *
     * 정상 흐름은 EXIT 명령으로 즉시 해제되지만, 브라우저 강제 종료·네트워크 단절로 EXIT가 누락될 수 있다.
     * 마지막 enter 후 [DETAIL_IDLE_TIMEOUT_MS](15분) 경과한 DETAIL demand를 회수한다.
     * STRATEGY demand는 대상 아님(registrar TTL이 관리 — 전략 호가 공백 차단).
     */
    @Scheduled(fixedRate = 60_000)
    fun evictIdleDetailSubscriptions() {
        val now = System.currentTimeMillis()
        lock.withLock {
            val staleCodes =
                detailDemands.entries
                    .filter { (_, lastSeenMs) -> (now - lastSeenMs) > detailIdleTimeoutMs }
                    .map { it.key }

            staleCodes.forEach { code ->
                detailDemands.remove(code)
                if (!isDemandedLocked(code)) subscribedSinceMs.remove(code)
                log.info("orderbook DETAIL idle 안전망 해제(EXIT 누락 추정): code={} idle>15min", code)
                unsubscribeIfUnownedLocked(code)
            }
        }
    }

    /**
     * 현재 호가 구독 중인 종목코드 목록(DETAIL ∪ STRATEGY).
     */
    fun current(): Set<String> =
        lock.withLock {
            (detailDemands.keys + strategyDemands.keys).toSet()
        }

    /**
     * 관리자 실시간 상태 스냅샷용 — 호가(H0UNASP0) 구독 code별 owner 목록 + 최초 구독 시각(read-only).
     *
     * 구독 로직 변경 0 — demand read만. owner 문자열 = 체결가 라우터와 표기 대칭:
     * - STRATEGY는 [ownerLabel]로 `strategy:` prefix 1회 부여(bare `86` → `strategy:86`,
     *   세션 식별자 `daytrade-paper:5` → `strategy:daytrade-paper:5`). 이미 `strategy:`로 시작하면 재부여 생략(이중 prefix 방어).
     * - DETAIL은 ownerId 미추적 → `detail`(현행 유지).
     */
    fun snapshotDemands(): List<OrderbookDemandSnapshot> =
        lock.withLock {
            val codes = detailDemands.keys + strategyDemands.keys
            codes.map { code ->
                val strategyOwners =
                    strategyDemands[code]?.map { ownerLabel(RealtimeDemandOwner.STRATEGY, it) } ?: emptyList()
                val detailOwner = if (detailDemands.containsKey(code)) listOf("detail") else emptyList()
                val owners = strategyOwners + detailOwner
                OrderbookDemandSnapshot(
                    code = code,
                    owners = owners.sorted(),
                    sinceMs = subscribedSinceMs[code],
                )
            }
        }

    companion object {
        private const val TR_ID_UNIFIED_ORDERBOOK = "H0UNASP0"

        /** DETAIL 호가 idle 안전망 timeout — 15분 (현재가 DETAIL grace 5분보다 길게, EXIT 누락 보완). */
        const val DETAIL_IDLE_TIMEOUT_MS = 15L * 60L * 1000L
    }
}

/**
 * 관리자 실시간 상태 스냅샷용 호가(H0UNASP0) demand 투영 (read-only).
 */
data class OrderbookDemandSnapshot(
    val code: String,
    val owners: List<String>,
    val sinceMs: Long?,
)
