package kr.spdaewoon.magicjar.consumer.ingestion

import jakarta.annotation.PreDestroy
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 실시간 체결가 KIS WS 구독 라우터 — owner demand 단위 (HOLDING/PENDING_BUY/STRATEGY/DETAIL).
 *
 * 종목 상세 진입·전략 후보·보유·미체결은 서로 다른 수요다.
 * 이 라우터는 그 owner 수요를 단위로 추적해 단순 합집합으로 섞이지 않게 KIS WS slot을 배정한다.
 *
 * ## watchlist 분리 (ADR 0124 결정 2 · 2026-06-13)
 * watchlist는 더 이상 KIS WS를 점유하지 않는다(5초 REST polling 전용 — [SubscriptionRouter]).
 * 따라서 본 라우터의 KIS WS slot 예산은 owner demand만 소비하고, eviction victim 후보는
 * "어느 owner demand에도 없는 잔여 구독"만 남는다(과거 watchlist priority 기반 eviction 폐지).
 * 같은 종목이 owner demand로 WS 구독되면 watchlist 화면은 그 WS tick으로 갱신된 더 신선한 quote를 읽는다.
 */
@Component
class RealtimeSubscriptionRouter(
    private val kisWsPort: KisWsPort,
    private val todayCandleSeedPort: TodayCandleSeedPort,
) {
    private val log = LoggerFactory.getLogger(RealtimeSubscriptionRouter::class.java)
    private val lock = ReentrantLock()

    private data class OwnerKey(
        val owner: RealtimeDemandOwner,
        val ownerId: String,
    )

    private data class Demand(
        val priority: Int,
        var expiresAtMs: Long,
        var lastAccessMs: Long,
        // 관리자 스냅샷용 — 해당 owner demand 최초 등록 시각(epoch ms). TTL refresh로 갱신되지 않는다(since 표시 안정).
        val subscribedSinceMs: Long = System.currentTimeMillis(),
    )

    private val demandsByCode: MutableMap<String, MutableMap<OwnerKey, Demand>> = linkedMapOf()
    private val pendingDebounce: MutableMap<String, Long> = mutableMapOf()

    /**
     * 구독 수립 시점 today-Hash 시드 전용 executor (단일 스레드).
     *
     * 시드는 공식 KIS REST 1콜(blocking ~최대 3s)이므로 router lock·caller path를 막지 않도록
     * lock 해제 후 비동기로 실행한다(자본/시세 path 지연 0). 시드 자체 실패는 graceful — 1d tick-only 합성 없이 DB/quote fallback.
     */
    private val seedExecutor =
        Executors.newSingleThreadExecutor(
            ThreadFactory { r -> Thread(r, "today-candle-seed").also { it.isDaemon = true } },
        )

    /**
     * 시드 in-flight/최근 완료 dedup — 짧은 시간 내 동일 code 재구독(DETAIL 재진입·STRATEGY 재후보)에
     * 중복 KIS 호출을 막는다. 값 = 마지막 시드 제출 시각(ms). cooldown 내 재제출 skip.
     */
    private val recentSeedMs: MutableMap<String, Long> = ConcurrentHashMap()
    private val seedCooldownMs = 60_000L

    private val detailDebounceMs = 200L

    // DETAIL은 EXIT 수신 시 즉시 해제한다. 이 TTL은 EXIT 누락/브라우저 비정상 종료 대비 안전 정리용이다.
    private val detailTtlMs = 5L * 60L * 1000L
    private val strategyTtlMs = 30L * 60L * 1000L

    // ADR 0114 §4-3 P3 — 보유 종목 구독 TTL. 60초 refresh가 갱신하므로 5분 grace로 일시 refresh 실패에도 끊김 방지.
    private val holdingTtlMs = 5L * 60L * 1000L

    // ADR 0114 §4-3 P3(Codex pending-buy 흡수) — 주문 제출~체결 사이 watch. 체결 이벤트가 release하나 미체결/취소 대비 TTL.
    private val pendingBuyTtlMs = 30L * 60L * 1000L

    fun request(
        owner: RealtimeDemandOwner,
        ownerId: String,
        code: String,
        priority: Int = owner.defaultPriority,
        ttlSeconds: Long? = null,
    ) {
        val newlySubscribed =
            lock.withLock {
                val now = System.currentTimeMillis()
                cleanupExpiredLocked(now)

                if (owner == RealtimeDemandOwner.DETAIL) {
                    pendingDebounce.entries.removeIf { (_, until) -> now > until }
                    val pendingUntil = pendingDebounce[code]
                    if (pendingUntil != null && now < pendingUntil) {
                        log.debug("realtime demand debounce skip: owner={} code={}", owner, code)
                        return@withLock false
                    }
                    pendingDebounce[code] = now + detailDebounceMs
                }

                val ownerKey = OwnerKey(owner, ownerId.ifBlank { owner.name })
                val ttlMs = (ttlSeconds?.coerceAtLeast(1L)?.times(1000L)) ?: owner.defaultTtlMs()
                val owners = demandsByCode.getOrPut(code) { linkedMapOf() }
                // 재요청(TTL refresh) 시 since는 최초 등록 시각을 보존 — 관리자 스냅샷 since 흔들림 방지.
                val existingSince = owners[ownerKey]?.subscribedSinceMs ?: now
                owners[ownerKey] =
                    Demand(
                        priority = priority,
                        expiresAtMs = now + ttlMs,
                        lastAccessMs = now,
                        subscribedSinceMs = existingSince,
                    )

                val subscribed = ensureSubscribedLocked(code, priority)
                log.info(
                    "realtime demand enter: owner={} ownerId={} code={} owners={}",
                    owner,
                    ownerKey.ownerId,
                    code,
                    owners.size,
                )
                subscribed
            }

        // 신규 subscribe는 KisWebSocketBridge.subscribe()가 tick 송신 전에 seed를 먼저 시도한다.
        // 이미 구독 중인 종목은 watchlist/재연결 등 기존 경로에서 seed가 누락됐을 수 있어 DETAIL·HOLDING·STRATEGY 진입 때 보강한다.
        if (!newlySubscribed) scheduleSeed(code)
    }

    /**
     * today-Hash 시드 비동기 제출. cooldown 내 중복 제출 skip(동일 code 재구독 시 중복 KIS 호출 방지).
     */
    private fun scheduleSeed(code: String) {
        val now = System.currentTimeMillis()
        val last = recentSeedMs[code]
        if (last != null && now - last < seedCooldownMs) {
            log.debug("today-candle seed cooldown skip: code={}", code)
            return
        }
        recentSeedMs[code] = now
        seedExecutor.submit {
            try {
                todayCandleSeedPort.seedTodayCandle(code)
            } catch (e: Exception) {
                log.warn("today-candle seed 비동기 실패(graceful — DB/quote fallback): code={} err={}", code, e.message)
            }
        }
    }

    fun release(
        owner: RealtimeDemandOwner,
        ownerId: String,
        code: String,
    ) = lock.withLock {
        val now = System.currentTimeMillis()
        cleanupExpiredLocked(now)

        val ownerKey = OwnerKey(owner, ownerId.ifBlank { owner.name })
        val owners = demandsByCode[code] ?: return@withLock
        if (owners.remove(ownerKey) == null) return@withLock
        if (owners.isEmpty()) {
            demandsByCode.remove(code)
            pendingDebounce.remove(code)
            releaseIfUnownedLocked(code)
        }
        log.info("realtime demand exit: owner={} ownerId={} code={} remaining={}", owner, ownerKey.ownerId, code, owners.size)
    }

    fun isExternallyDemanded(code: String): Boolean =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode[code]?.isNotEmpty() == true
        }

    fun activeCodes(): Set<String> =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode.keys.toSet()
        }

    /**
     * 관리자 실시간 상태 스냅샷용 — code별 owner 목록 + 최초 구독 시각(epoch ms) read-only 투영.
     *
     * 구독 로직 변경 0 — 현재 demand 맵 read만 수행한다(만료 정리 후 살아있는 demand만).
     * owner 문자열 = `<owner enum lowercase>:<bare ownerId>` (예: `strategy:86`·`detail:74683223`·`holding:005930`·`pending_buy:123`).
     * ownerId는 producer가 bare로 넣지만, 롤링 배포·미발견 producer 잔존 대비 prefix 중복 부여를 방어한다
     * (ownerId가 이미 `<owner>:`로 시작하면 재부여 생략 → `strategy:strategy:86` 이중 prefix 회피).
     * since = 해당 code demand 중 가장 오래된 subscribedSinceMs (최초 구독 시각 의미).
     */
    fun snapshotDemands(): List<RealtimeDemandSnapshot> =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode.map { (code, owners) ->
                RealtimeDemandSnapshot(
                    code = code,
                    owners = owners.keys.map { ownerLabel(it.owner, it.ownerId) }.sorted(),
                    sinceMs = owners.values.minOfOrNull { it.subscribedSinceMs },
                )
            }
        }

    fun priorityOf(code: String): Int? =
        lock.withLock {
            cleanupExpiredLocked(System.currentTimeMillis())
            demandsByCode[code]?.values?.minOfOrNull { it.priority }
        }

    @Scheduled(fixedRate = 60_000)
    fun evictExpiredDemands() {
        lock.withLock {
            val expiredCodes = cleanupExpiredLocked(System.currentTimeMillis())
            expiredCodes.forEach { releaseIfUnownedLocked(it) }
        }
        // 만료된 시드 cooldown 정리 — 재구독 시 fresh 시드 허용.
        val now = System.currentTimeMillis()
        recentSeedMs.entries.removeIf { (_, ts) -> now - ts > seedCooldownMs }
    }

    @PreDestroy
    fun shutdown() {
        seedExecutor.shutdown()
    }

    /**
     * @return WS 구독을 새로 발사했으면 true (seed는 KisWsPort.subscribe 내부에서 tick 송신 전 수행). 이미 구독 중·큐잉이면 false.
     */
    private fun ensureSubscribedLocked(
        code: String,
        priority: Int,
    ): Boolean {
        kisWsPort.connect()
        val subscribed = kisWsPort.subscribedCodes()
        if (code in subscribed) return false

        if (subscribed.size >= SubscriptionPolicy.PRICE_TICK_MAX) {
            val victim = findEvictionCandidateLocked(subscribed)
            if (victim == null) {
                log.warn(
                    "realtime demand queued: code={} priority={} subscribed={} max={}",
                    code,
                    priority,
                    subscribed.size,
                    SubscriptionPolicy.PRICE_TICK_MAX,
                )
                return false
            }
            kisWsPort.unsubscribe(listOf(victim))
            log.info("realtime demand evict unowned: victim={} new={} priority={}", victim, code, priority)
        }

        kisWsPort.subscribe(listOf(code))
        return true
    }

    /**
     * eviction victim 후보 — 어느 owner demand에도 없는 잔여 구독 (ADR 0124 결정 2).
     *
     * watchlist는 더 이상 WS를 점유하지 않으므로(polling 전용) eviction victim 후보는
     * "demand 맵에 없는 + HOLDING demand도 아닌" 구독뿐이다(release 누락·재연결 잔여 등 정리 대상).
     * 데이터를 요구하는 모든 owner는 demandsByCode에 등록되므로, 후보가 없으면(=전부 demand) queued 처리한다.
     */
    private fun findEvictionCandidateLocked(subscribed: Set<String>): String? {
        val demandedCodes = demandsByCode.keys
        // ADR 0114 §4-3 P3 — HOLDING owner code는 evict 절대 금지(보유 종목 구독 끊김 = 청산 재료 공백 = 자본 risk).
        val holdingCodes = codesWithHoldingDemandLocked()
        return subscribed.firstOrNull { it !in demandedCodes && it !in holdingCodes }
    }

    private fun releaseIfUnownedLocked(code: String) {
        if (demandsByCode[code]?.isNotEmpty() == true) return

        if (code in kisWsPort.subscribedCodes()) {
            kisWsPort.unsubscribe(listOf(code))
            log.info("realtime demand unsubscribed: code={}", code)
        }
    }

    private fun cleanupExpiredLocked(now: Long): Set<String> {
        val changedCodes = mutableSetOf<String>()
        val iterator = demandsByCode.iterator()
        while (iterator.hasNext()) {
            val (code, owners) = iterator.next()
            val before = owners.size
            owners.entries.removeIf { (_, demand) -> now > demand.expiresAtMs }
            if (owners.size != before) changedCodes.add(code)
            if (owners.isEmpty()) {
                iterator.remove()
                pendingDebounce.remove(code)
                changedCodes.add(code)
            }
        }
        return changedCodes
    }

    /** HOLDING owner demand가 있는 code 집합 (ADR 0114 §4-3 — evict 후보에서 무조건 제외). 호출자가 lock 보유. */
    private fun codesWithHoldingDemandLocked(): Set<String> =
        demandsByCode
            .filterValues { owners -> owners.keys.any { it.owner == RealtimeDemandOwner.HOLDING } }
            .keys

    private fun RealtimeDemandOwner.defaultTtlMs(): Long =
        when (this) {
            RealtimeDemandOwner.DETAIL -> detailTtlMs
            RealtimeDemandOwner.STRATEGY -> strategyTtlMs
            // HOLDING은 보유 동안 refresh로 갱신(60초 주기) — 기본 TTL은 refresh 주기보다 넉넉(grace 포함).
            RealtimeDemandOwner.HOLDING -> holdingTtlMs
            RealtimeDemandOwner.PENDING_BUY -> pendingBuyTtlMs
        }
}

/**
 * 관리자 실시간 상태 스냅샷용 체결가 demand 투영 (read-only).
 *
 * 실제 trId 라벨은 활성 채널(H0UNCNT0 통합 체결가)을 publisher가 표기한다(ADR 0124).
 *
 * @param code 종목코드.
 * @param owners owner 식별자 목록(`<owner>:<ownerId>`, 정렬).
 * @param sinceMs 최초 구독 시각(epoch ms) — 없으면 null.
 */
data class RealtimeDemandSnapshot(
    val code: String,
    val owners: List<String>,
    val sinceMs: Long?,
)

/**
 * 스냅샷 owner 라벨 = `<owner enum lowercase>:<bare ownerId>` (단일 prefix LOCK).
 *
 * ownerId가 이미 `<owner>:`로 시작하면(롤링 배포 잔존·미발견 producer) prefix 재부여를 생략해 `strategy:strategy:86`
 * 이중 prefix를 방어한다. 체결가·호가 라우터가 공유하는 단일 라벨링 규약.
 */
internal fun ownerLabel(
    owner: RealtimeDemandOwner,
    ownerId: String,
): String {
    val prefix = "${owner.name.lowercase()}:"
    return if (ownerId.startsWith(prefix)) ownerId else "$prefix$ownerId"
}

enum class RealtimeDemandOwner(
    val defaultPriority: Int,
) {
    // priority 낮을수록 우선(eviction 후보에서 보호). 재정렬: HOLDING > PENDING_BUY > STRATEGY > DETAIL (ADR 0124).
    // 근거: 보유 청산(스탑로스) tick 공급 최우선, 매매 경로(STRATEGY) > 관찰 경로(DETAIL).

    // ADR 0114 §4-3 P3 — 보유 종목(체결 후 quantity>0). 최우선·evict 절대 금지(findEvictionCandidateLocked 제외 LOCK).
    HOLDING(defaultPriority = 0),

    // ADR 0114 §4-3 P3(Codex 흡수) — 주문 제출~체결 사이 pending watch. 체결 전 구독 보장(체결 통보 수신 보장).
    PENDING_BUY(defaultPriority = 5),

    // ADR 0124 — 매매 경로(전략 ACTIVE 평가). 관찰 경로(DETAIL)보다 우선. 40→10.
    STRATEGY(defaultPriority = 10),

    // ADR 0124 — 관찰 경로(종목 상세 화면 표시 중). 매매 경로 다음. 10→20.
    DETAIL(defaultPriority = 20),
    ;

    companion object {
        fun from(value: String?): RealtimeDemandOwner? =
            value
                ?.trim()
                ?.uppercase()
                ?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}
