package kr.spdaewoon.magicjar.consumer.ingestion

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * KIS WS 구독 슬롯 단일 회계기 (ADR 0123 R1 — `WsSlotBudgetLedger`).
 *
 * ## 책임
 * 체결가(H0UNCNT0)·호가(H0UNASP0)·시간외(H0STOUP0)·체결통보(H0STCNI0/9) WS 구독을
 * **"채널 = (code, trId)" 단위**로 단일 회계한다. 기존엔 [RealtimeSubscriptionRouter](체결가)와
 * `OrderbookSubscriptionRouter`(호가)가 각각 부분 한도만 관리하고 NXT 이중 발사를 회계하지 않아,
 * 총 점유가 40을 넘어도 시스템이 인지하지 못한 채 계속 subscribe를 발사 → OPSP0008(MAX SUBSCRIBE OVER)
 * 거부가 발생했다(2026-06-11 후성 093370 분봉 35/92 적재 결함, ADR 0123 §1-1).
 *
 * ## R1 범위 (동작 변경 최소)
 * - **회계만** 수행한다 — 점유 등록/취소 + available 산식 + OPSP0008 거부 회계 보정.
 * - **선제 eviction·채널 다이어트는 R2/R3** — 본 라운드는 차단 없이 관측+graceful 보정까지.
 * - `MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=false`(롤백 toggle) 시 register/release/reject 모두 no-op
 *   → 현행 동작 회귀 (KisWebSocketBridge는 ledger와 무관하게 기존 subscribedCodes로 동작).
 *
 * ## KIS-first 한도 (헌법 §3 — 자체 추론 0)
 * 한도 단위 = open_map key (tr_id, tr_key) 조합 = 채널 단위.
 * KIS 공식 예제 kis_auth.py `__runner()`가 40을 hard enforce:
 *   `if len(open_map.keys()) > 40: raise ValueError("Subscription's max is 40")`
 * 포털 명시 한도 미공개(UNCERTAIN) → 40을 보수적 운영 상한으로 채택하되 상수가 아닌 ENV로 운영.
 * KIS-first raw 출처:
 *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
 *
 * ## thread-safety
 * subscribe/unsubscribe는 RealtimeSubscriptionRouter·Kafka listener thread, 거부 보정은 Reactor WS thread에서
 * 호출된다 → ConcurrentHashMap.newKeySet() + AtomicLong로 가시성·원자성 보장 (KisWebSocketBridge 패턴 정합).
 *
 * @param meterRegistry Prometheus 노출(미존재 환경=qa fixture에선 ObjectProvider로 graceful no-op).
 */
@Component
class WsSlotBudgetLedger(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
    @Value("\${magicjar.kis.ws.slot-ledger.enabled:true}")
    private val ledgerEnabled: Boolean = true,
    @Value("\${magicjar.kis.ws.max-slots:40}")
    private val maxSlots: Int = SubscriptionPolicy.TOTAL_SLOTS,
) {
    private val log = LoggerFactory.getLogger(WsSlotBudgetLedger::class.java)

    /**
     * 점유 채널 집합 — key = "trId|code" (코드 × tr_id 단일 view).
     * KIS open_map (tr_id, tr_key) 조합과 1:1 — 체결통보(reserve 1)는 별도 카운트하지 않고 available 산식에서 차감.
     */
    private val occupiedChannels: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 거부 누적(OPSP0008) — metric Counter 노출 + WARN throttle 합산용. */
    private val rejectedTotal = AtomicLong(0L)

    /** OPSP0008 WARN throttle — 마지막 WARN 발사 epoch millis (반복 거부 폭주 억제). */
    private val lastRejectWarnAtMs = AtomicLong(0L)

    companion object {
        // Micrometer dot-notation → Prometheus snake_case 자동 변환:
        //   magicjar.kis.ws.slots.occupied  → magicjar_kis_ws_slots_occupied (spec §3-5 metric 명세 정합)
        const val METRIC_SLOTS_OCCUPIED = "magicjar.kis.ws.slots.occupied"
        const val METRIC_SLOTS_AVAILABLE = "magicjar.kis.ws.slots.available"
        const val METRIC_SUBSCRIBE_REJECTED = "magicjar.kis.ws.subscribe.rejected"

        /** OPSP0008 반복 거부 WARN 1회/주기 throttle window (ms) — OPSP8996 grace 패턴 정합. */
        private const val REJECT_WARN_THROTTLE_MS = 60_000L
    }

    init {
        if (ledgerEnabled) {
            registerMetrics()
            log.info("WsSlotBudgetLedger 활성 — maxSlots={} reserved={}", maxSlots, SubscriptionPolicy.EXECUTION_NOTICE_RESERVED)
        } else {
            log.info("WsSlotBudgetLedger 비활성(MAGICJAR_KIS_WS_SLOT_LEDGER_ENABLED=false) — 회계 no-op, 현행 동작 회귀")
        }
    }

    private fun channelKey(
        trId: String,
        code: String,
    ): String = "$trId|$code"

    /**
     * 채널 점유 등록 (subscribe 발사 직후 호출).
     *
     * R1은 차단하지 않는다 — 한도 초과여도 등록 + 관측만. (선제 차단은 R2 eviction.)
     * 이미 점유 중인 채널 재등록은 멱등(중복 카운트 0).
     *
     * @return 등록 후 현재 점유 채널 수.
     */
    fun register(
        trId: String,
        code: String,
    ): Int {
        if (!ledgerEnabled) return occupiedChannels.size
        occupiedChannels.add(channelKey(trId, code))
        return occupiedChannels.size
    }

    /**
     * 채널 점유 취소 (unsubscribe 발사 직후 호출).
     * 미점유 채널 release는 멱등(no-op).
     */
    fun release(
        trId: String,
        code: String,
    ): Int {
        if (!ledgerEnabled) return occupiedChannels.size
        occupiedChannels.remove(channelKey(trId, code))
        return occupiedChannels.size
    }

    /**
     * OPSP0008(MAX SUBSCRIBE OVER) 거부 회계 보정 (ADR 0123 §3-4 graceful path).
     *
     * KIS가 (trId, code) 채널 subscribe를 거부 → 해당 채널은 실제 미점유.
     * ledger에서 점유를 취소(보정)하고 거부 카운트를 증가시킨다. WARN 로그는 throttle(1회/주기).
     *
     * 본 메서드는 회계 보정만 수행 — subscribedCodes 정합·gap-fill·polling 연결은 KisWebSocketBridge 책임.
     *
     * @return Pair(보정 후 점유 채널 수, WARN 발사 여부) — WARN 여부는 호출자 로깅 정합 참고용.
     */
    fun onRejected(
        trId: String,
        code: String,
    ): RejectionOutcome {
        if (!ledgerEnabled) return RejectionOutcome(occupiedChannels.size, warnEmitted = false)
        val removed = occupiedChannels.remove(channelKey(trId, code))
        val total = rejectedTotal.incrementAndGet()
        incrementRejectedCounter(trId)

        // WARN throttle — 반복 거부 폭주 시 1회/주기만 WARN, 그 사이는 debug.
        val now = System.currentTimeMillis()
        val lastWarn = lastRejectWarnAtMs.get()
        val warn = now - lastWarn >= REJECT_WARN_THROTTLE_MS
        if (warn && lastRejectWarnAtMs.compareAndSet(lastWarn, now)) {
            log.warn(
                "KIS WS 구독 거부 OPSP0008(MAX SUBSCRIBE OVER) — ledger 회계 보정: " +
                    "trId={} code={} removed={} occupied={} rejectedTotal={} (반복 거부는 {}s마다 1회 WARN)",
                trId,
                code,
                removed,
                occupiedChannels.size,
                total,
                REJECT_WARN_THROTTLE_MS / 1000,
            )
        } else {
            log.debug(
                "KIS WS 구독 거부 OPSP0008 (throttle): trId={} code={} occupied={} rejectedTotal={}",
                trId,
                code,
                occupiedChannels.size,
                total,
            )
        }
        return RejectionOutcome(occupiedChannels.size, warnEmitted = warn)
    }

    /** 현재 점유 채널 수 (코드 × tr_id). */
    fun occupiedCount(): Int = occupiedChannels.size

    /**
     * 관리자 실시간 상태 스냅샷용 — 점유 채널의 tr_id별 카운트 + tr_id별 종목코드 목록 + 한도(read-only).
     *
     * 회계 로직 변경 0 — occupiedChannels(key="trId|code") read만 (point-in-time 1회 순회로 카운트·코드 목록을
     * 동시에 산출 — 동시 변경 시 미세 불일치 가능하나 관측 레이어라 무해). ledger 비활성(toggle off) 시 used=0.
     *
     * codesByTrId는 "H0UNCNT0 15개가 뭔지" drill-down용 — trId별 정렬된 종목코드 목록.
     *
     * @return SlotSnapshot(used=점유 채널 수, max=maxSlots, byTrId=tr_id별 점유 카운트, codesByTrId=tr_id별 종목코드 목록).
     */
    fun snapshot(): SlotSnapshot {
        // occupiedChannels(key="trId|code")를 1회 순회해 trId → 정렬된 code 목록으로 그룹화.
        val codesByTrId =
            occupiedChannels
                .groupBy({ it.substringBefore('|') }, { it.substringAfter('|') })
                .mapValues { (_, codes) -> codes.sorted() }
                .toSortedMap()
        val byTrId = codesByTrId.mapValues { (_, codes) -> codes.size }.toSortedMap()
        return SlotSnapshot(
            used = occupiedChannels.size,
            max = maxSlots,
            byTrId = byTrId,
            codesByTrId = codesByTrId,
        )
    }

    /**
     * 관리자 슬롯 회계 스냅샷.
     * @param used 점유 채널 수(code × tr_id).
     * @param max 운영 상한(maxSlots — 기본 40, ENV `magicjar.kis.ws.max-slots`).
     * @param byTrId tr_id별 점유 채널 수.
     * @param codesByTrId tr_id별 점유 종목코드 목록(정렬) — drill-down용.
     */
    data class SlotSnapshot(
        val used: Int,
        val max: Int,
        val byTrId: Map<String, Int>,
        val codesByTrId: Map<String, List<String>> = emptyMap(),
    )

    /**
     * 가용 slot = maxSlots - reserved(체결통보 1) - 점유 채널 수 (음수 방지).
     * R1은 산식·관측만 — 발사 직전 차단(선제 eviction)은 R2.
     */
    fun availableSlots(): Int =
        SubscriptionPolicy.channelSlotsAvailable(
            occupiedChannels = occupiedChannels.size,
            maxSlots = maxSlots,
        )

    /** 특정 채널 점유 여부 (테스트·진단). */
    fun isOccupied(
        trId: String,
        code: String,
    ): Boolean = occupiedChannels.contains(channelKey(trId, code))

    fun rejectedTotal(): Long = rejectedTotal.get()

    fun enabled(): Boolean = ledgerEnabled

    private fun registerMetrics() {
        val registry = meterRegistryProvider.ifAvailable ?: return
        // 점유 게이지 — 현재 점유 채널 수 (40 대비). spec §3-5 magicjar_kis_ws_slots_occupied.
        Gauge
            .builder(METRIC_SLOTS_OCCUPIED, occupiedChannels) { it.size.toDouble() }
            .description("KIS WS 점유 구독 채널 수 (code × tr_id) — ADR 0123 R1")
            .register(registry)
        // 가용 게이지 — spec §3-5 magicjar_kis_ws_slots_available.
        Gauge
            .builder(METRIC_SLOTS_AVAILABLE, this) { it.availableSlots().toDouble() }
            .description("KIS WS 가용 구독 slot (maxSlots - reserved - 점유) — ADR 0123 R1")
            .register(registry)
    }

    private fun incrementRejectedCounter(trId: String) {
        val registry = meterRegistryProvider.ifAvailable ?: return
        // spec §3-5 magicjar_kis_ws_subscribe_rejected_total (tag: msg_cd, tr_id).
        registry
            .counter(
                METRIC_SUBSCRIBE_REJECTED,
                Tags.of("msg_cd", "OPSP0008", "tr_id", trId),
            ).increment()
    }

    /**
     * OPSP0008 거부 회계 보정 결과.
     * @param occupiedAfter 보정 후 점유 채널 수.
     * @param warnEmitted 본 거부에서 throttle 통과 WARN이 발사됐는지(반복 거부 합산 진단용).
     */
    data class RejectionOutcome(
        val occupiedAfter: Int,
        val warnEmitted: Boolean,
    )
}
