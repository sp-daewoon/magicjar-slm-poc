package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PostConstruct
import kr.spdaewoon.magicjar.consumer.order.HoldingPositionIndex
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 보유 종목 KIS WS 구독 보장 — ADR 0114 §4-3 P3 ([RealtimeDemandOwner.HOLDING] 등록·해제).
 *
 * ## 왜 필요한가 (ADR 0114 §4-3)
 * 40 slot 경합에서 보유 종목이 evict되면 tick·1m 캔들이 끊겨 **청산 평가 재료가 사라진다**(자본 risk).
 * HOLDING owner는 [RealtimeSubscriptionRouter.findEvictionCandidateLocked]에서 절대 제외되므로,
 * 본 registrar가 보유 code를 HOLDING demand로 등록하면 구독이 끊기지 않는다.
 *
 * ## Codex 대비 보강 (POSITION owner 흡수 + 보호 강화)
 * - Codex `PositionRealtimeWatchService`는 owner를 priority 5 + TTL로만 보호(slot 부족 시 evict 가능 — 약함).
 *   우리는 priority 0 + **evict 금지 LOCK**(enum HOLDING).
 * - 조회 중복 금지: 보유 종목 집합은 [HoldingPositionIndex](tick watcher와 공유 — DB refresh 1곳)를 재사용.
 * - Codex pending-buy watch도 흡수([watchPendingBuy]/[releasePendingBuy] — PENDING_BUY owner).
 *
 * ## 등록/해제 (ADR 0114 §4-3)
 * - **등록**: 부팅 1회 + 60초 refresh(보유 lot 전체) + 체결 시 즉시([onBuyFilled]).
 * - **해제**: 전량 청산(quantity=0) 시 release. refresh가 사라진 code를 인덱스에서 빼므로 자연 해제도 동작.
 *
 * // AI 호출 0 — 인프라 구독 라우팅 (ADR 0023 §2-4)
 */
@Component
class HoldingDemandRegistrar(
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val holdingPositionIndex: HoldingPositionIndex,
    @Value("\${magicjar.autotrading.holding-watch.enabled:true}")
    private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(HoldingDemandRegistrar::class.java)
    private val registeredHoldingCodes = ConcurrentHashMap.newKeySet<String>()

    /** 부팅 직후 보유 종목 구독 복구(시스템 재시작 시 보유분 구독 공백 0). 인덱스 refresh 이후 register. */
    @PostConstruct
    fun bootstrap() {
        if (!enabled) return
        // 인덱스가 아직 비었으면(스케줄 첫 tick 전) 한 번 강제 refresh 후 등록.
        holdingPositionIndex.refresh()
        registerAllHolding()
    }

    /** 60초 주기 — 보유 종목 HOLDING demand 갱신(TTL refresh) + 신규 보유 등록 + 청산 종목 명시 해제. */
    @Scheduled(fixedDelayString = "\${magicjar.autotrading.holding-watch.refresh-delay-ms:60000}", initialDelay = 15_000)
    fun refreshHoldingDemands() {
        if (!enabled) return
        holdingPositionIndex.refresh()
        registerAllHolding()
    }

    private fun registerAllHolding() {
        val codes = holdingPositionIndex.holdingKeys().map { it.code }.toSet()
        val staleCodes = registeredHoldingCodes.filter { it !in codes }
        staleCodes.forEach { code ->
            releaseHolding(code, reason = "refresh-missing")
        }
        val activeCodes = mutableSetOf<String>()
        codes.forEach { code ->
            if (requestHolding(code)) activeCodes.add(code)
        }
        registeredHoldingCodes.clear()
        registeredHoldingCodes.addAll(activeCodes)
        if (codes.isNotEmpty()) log.debug("HoldingDemandRegistrar refresh: holdingCodes={}", codes.size)
    }

    /** 체결(BUY filled) 즉시 등록 — 60초 기다리지 않고 구독 보장(체결~첫 refresh 사이 공백 0). */
    fun onBuyFilled(code: String) {
        if (!enabled) return
        requestHolding(code)
    }

    /** 전량 청산(quantity=0) 시 해제. 잔량 있으면 호출 금지(refresh가 유지). */
    fun onFullyExited(code: String) {
        if (!enabled) return
        releaseHolding(code, reason = "fully-exited")
    }

    private fun releaseHolding(
        code: String,
        reason: String,
    ) {
        runCatching {
            realtimeSubscriptionRouter.release(owner = RealtimeDemandOwner.HOLDING, ownerId = holdingOwnerId(code), code = code)
        }.onFailure { e -> log.warn("HOLDING demand release 실패: code={} err={}", code, e.message) }
        registeredHoldingCodes.remove(code)
        log.info("HOLDING demand release: code={} reason={}", code, reason)
    }

    /** 주문 제출~체결 사이 pending watch (Codex 흡수 — PENDING_BUY owner). */
    fun watchPendingBuy(
        orderId: Long?,
        code: String,
    ) {
        if (!enabled) return
        runCatching {
            realtimeSubscriptionRouter.request(owner = RealtimeDemandOwner.PENDING_BUY, ownerId = pendingBuyOwnerId(orderId), code = code)
        }.onFailure { e -> log.warn("PENDING_BUY watch 실패: code={} err={}", code, e.message) }
    }

    fun releasePendingBuy(
        orderId: Long?,
        code: String,
    ) {
        if (!enabled) return
        runCatching {
            realtimeSubscriptionRouter.release(owner = RealtimeDemandOwner.PENDING_BUY, ownerId = pendingBuyOwnerId(orderId), code = code)
        }.onFailure { e -> log.warn("PENDING_BUY release 실패: code={} err={}", code, e.message) }
    }

    private fun requestHolding(code: String): Boolean =
        runCatching {
            realtimeSubscriptionRouter.request(owner = RealtimeDemandOwner.HOLDING, ownerId = holdingOwnerId(code), code = code)
            registeredHoldingCodes.add(code)
            true
        }.onFailure { e -> log.warn("HOLDING demand request 실패: code={} err={}", code, e.message) }
            .getOrDefault(false)

    // bare ownerId — prefix(`holding:`/`pending_buy:`)는 스냅샷 직렬화에서 1회 부여(이중 prefix 회피).
    // request/release가 동일 함수를 쓰므로 OwnerKey 매칭 유지(holding=code, pending-buy=orderId 단위 멱등).
    private fun holdingOwnerId(code: String): String = code

    private fun pendingBuyOwnerId(orderId: Long?): String = "${orderId ?: "unknown"}"
}
