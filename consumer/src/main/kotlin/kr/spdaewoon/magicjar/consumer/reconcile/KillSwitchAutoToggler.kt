package kr.spdaewoon.magicjar.consumer.reconcile

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import kr.spdaewoon.magicjar.domain.reconcile.port.ReconcileEventPublisher
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `reconcile.drift.v1` Kafka listener — drift 검출 시 KillSwitch auto-ON 토글 (ADR 0043·0070 §2-11).
 *
 * ### 흐름 (ADR 0070 §2-11 자본 보호 step 5·6)
 * ```
 * 1. reconcile.drift.v1 listen (payload: userId, symbol, qtyDiff, ...)
 * 2. race-safe guard B (Grace Window) — 마지막 ORDER_FILLED 후 5분 내면 ON 보류
 * 3. race-safe guard C (N-Strike) — (userId, symbol) 5분 sliding window 안 N=2 이상이어야 ON
 * 4. user별 idempotency 체크 — 5분 내 이미 engaged면 skip
 * 5. KillSwitchTogglePort.engage(userId, reason="POSITION_DRIFT")
 *    — 어댑터 부재 시 KillSwitchJpaRepository.enableKillSwitch baseline reuse (graceful fallback)
 * 6. killswitch.engaged.v1 publish (triggeredBy=AUTO)
 * ```
 *
 * ### race-safe guard B·C 사유 (2026-05-21 사용자 dogfooding)
 * 사용자 09:43 매수 → 11:05 KillSwitch 자동 ON (false positive 결함). KIS WS 체결 후 KIS REST 잔고 인덱스
 * 반영 race window 또는 다른 잠시적 drift 1회만으로 KillSwitch ON되는 결함 — 정상 거래마다 ON 발생.
 *
 * - B (Grace Window): 마지막 ORDER_FILLED 후 5분간 drift 검출돼도 ON 보류 (race window 자체 가드).
 * - C (N-Strike): 같은 (userId, symbol) 5분 sliding window 안 drift 2회 이상이면 ON (1회는 잠시적 noise 가능).
 *
 * 두 가드는 순차 — B에서 skip되면 strike 카운터 미증가 (publish 자체가 reconcile A 단계에서 차단되므로 사실상
 * 본 listener까지 도달하면 publish 통과한 drift). C에서 미달이면 카운터만 증가하고 ON 보류.
 *
 * ### 다중 종목 drift 시 idempotency
 * 한 user의 reconcile 1회 실행에서 N개 종목 drift 검출 시 reconcile.drift.v1 N건 publish됨.
 * KillSwitch는 user당 1회만 engage — 5분 sliding window in-memory idempotency 캐시.
 * (Redis 분산락은 cron 자체에 적용 — 본 listener는 process-local 정합으로 충분)
 *
 * ### Phase 2 graceful fallback
 * - `KillSwitchTogglePort` 어댑터 부재 시 — `KillSwitchJpaRepository` baseline reuse (이미 존재).
 *   KillSwitchJpaRepository.enableKillSwitch는 ADR 0042 Bootstrap·ADR 0043 baseline에서 검증됨.
 * - `OrderRequestRepository` 어댑터 부재 시 — guard B는 skip (grace 미적용). guard C는 정상 동작.
 *
 * ### 단일 JVM in-memory 정합 (Phase 1)
 * `strikeWindow`는 ConcurrentHashMap — 단일 JVM 가정. Redis 분산 정합은 향후 Phase 2.
 * 메모리 cap — 10000 entry 초과 시 oldest eviction (보수적 cap 단독, 정확한 LRU 미도입).
 *
 * AI 호출 0 — T-RECON 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class KillSwitchAutoToggler(
    private val eventPublisher: ReconcileEventPublisher,
    private val killSwitchTogglePortProvider: ObjectProvider<KillSwitchTogglePort>,
    private val killSwitchJpaRepository: KillSwitchJpaRepository,
    private val objectMapper: ObjectMapper,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
) {
    private val log = LoggerFactory.getLogger(KillSwitchAutoToggler::class.java)

    companion object {
        const val TOPIC = KafkaTopics.Reconcile.DRIFT
        const val GROUP = "magicjar-consumer-reconcile-killswitch"
        private const val REASON = "POSITION_DRIFT"
        private const val TRIGGERED_BY = "AUTO"
        private const val UPDATED_BY = "consumer"
        private const val IDEMPOTENCY_WINDOW_MS = 5 * 60 * 1000L // 5분
        internal const val GRACE_WINDOW_MS = 5 * 60 * 1000L // race-safe guard B
        internal const val STRIKE_WINDOW_MS = 5 * 60 * 1000L // race-safe guard C sliding window
        internal const val STRIKE_THRESHOLD = 2 // race-safe guard C — N=2
        private const val STRIKE_MAP_CAP = 10_000 // 메모리 보호
    }

    // user별 마지막 engage 시각 — process-local idempotency
    private val lastEngagedAt = ConcurrentHashMap<String, Long>()

    // race-safe guard C — (userId, symbol) → drift 발생 epochMs 리스트 (5분 sliding window)
    internal val strikeWindow = ConcurrentHashMap<Pair<String, String>, MutableList<Long>>()

    /**
     * Test 진입점 — 시계 주입 (Clock 미도입, 가장 가벼운 functional seam 패턴).
     */
    internal var nowSupplier: () -> Long = { System.currentTimeMillis() }

    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP,
    )
    fun onDriftDetected(record: ConsumerRecord<String, String>) {
        try {
            val value = record.value() ?: return

            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
            val userId = payload["userId"]?.toString() ?: return
            val symbol = payload["symbol"]?.toString() ?: "?"

            val now = nowSupplier()

            // race-safe guard B (Grace Window) — 마지막 ORDER_FILLED 5분 내면 ON 보류
            if (insideGraceWindow(userId, now)) {
                log.info(
                    "KillSwitch grace window — 마지막 ORDER_FILLED 5분 내, ON 보류 (race-safe guard B): " +
                        "userId={} symbol={}",
                    userId,
                    symbol,
                )
                return
            }

            // race-safe guard C (N-Strike) — (userId, symbol) 5분 window 안 N=2 미달이면 카운터 증가만
            if (!recordAndCheckStrike(userId, symbol, now)) {
                return
            }

            // idempotency — 같은 user의 다중 종목 drift는 KillSwitch 1회만 engage
            val last = lastEngagedAt[userId]
            if (last != null && (now - last) < IDEMPOTENCY_WINDOW_MS) {
                log.debug(
                    "KillSwitch engage idempotency skip — 5분 내 이미 engaged: userId={} symbol={}",
                    userId,
                    symbol,
                )
                return
            }

            engage(userId)
            lastEngagedAt[userId] = now
            // engage 후 strike window 리셋 — 동일 user 모든 symbol 카운터 클리어
            strikeWindow.keys.removeAll { it.first == userId }
            eventPublisher.publishKillSwitchEngaged(userId, REASON, TRIGGERED_BY)
            log.warn(
                "KillSwitch auto-ON: userId={} reason={} triggeredBy={}",
                userId,
                REASON,
                TRIGGERED_BY,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitchAutoToggler 처리 실패: offset={} err={}",
                record.offset(),
                e.message,
                e,
            )
        }
    }

    /**
     * race-safe guard B — 마지막 ORDER_FILLED 5분 내 여부.
     *
     * Phase 2 graceful: `OrderRequestRepository` 어댑터 부재 또는 조회 실패 시 false (grace 미적용, ON 진행).
     */
    private fun insideGraceWindow(
        userId: String,
        nowMs: Long,
    ): Boolean {
        val repo = orderRequestRepositoryProvider.getIfAvailable() ?: return false
        val lastFilledAt: Instant =
            runCatching { repo.findLastFilledAt(userId) }
                .getOrElse { e ->
                    log.warn(
                        "OrderRequestRepository.findLastFilledAt 호출 실패 — guard B skip: userId={} err={}",
                        userId,
                        e.message,
                    )
                    null
                } ?: return false
        return (nowMs - lastFilledAt.toEpochMilli()) < GRACE_WINDOW_MS
    }

    /**
     * race-safe guard C — (userId, symbol) 5분 sliding window 안 strike 누적.
     *
     * @return true: threshold 도달 → ON 진행 / false: 미달 → ON 보류 (카운터만 증가)
     */
    private fun recordAndCheckStrike(
        userId: String,
        symbol: String,
        nowMs: Long,
    ): Boolean {
        // 메모리 cap 보호 — 초과 시 oldest key 1건 제거 (보수적 cap)
        if (strikeWindow.size >= STRIKE_MAP_CAP) {
            strikeWindow.keys.firstOrNull()?.let { strikeWindow.remove(it) }
        }
        val key = userId to symbol
        val hits = strikeWindow.computeIfAbsent(key) { mutableListOf() }
        synchronized(hits) {
            val cutoff = nowMs - STRIKE_WINDOW_MS
            hits.removeAll { it < cutoff }
            hits.add(nowMs)
            if (hits.size < STRIKE_THRESHOLD) {
                log.info(
                    "KillSwitch strike 누적 ({}/{}) — ON 보류 (race-safe guard C): userId={} symbol={}",
                    hits.size,
                    STRIKE_THRESHOLD,
                    userId,
                    symbol,
                )
                return false
            }
        }
        return true
    }

    /**
     * KillSwitch enable — Phase 2 port 우선, 부재 시 baseline JPA repo reuse.
     */
    internal fun engage(userId: String) {
        val port = killSwitchTogglePortProvider.getIfAvailable()
        if (port != null) {
            try {
                port.engage(userId, REASON)
                return
            } catch (e: Exception) {
                log.error(
                    "KillSwitchTogglePort.engage 실패 — baseline JPA로 fallback: userId={} err={}",
                    userId,
                    e.message,
                )
            }
        }
        // baseline JPA fallback (ADR 0042·0043 baseline)
        try {
            killSwitchJpaRepository.enableKillSwitch(
                userId = userId,
                reason = REASON,
                updatedBy = UPDATED_BY,
            )
        } catch (e: Exception) {
            log.error(
                "KillSwitchJpaRepository.enableKillSwitch 실패 — 자본 사고 노출 risk: userId={} err={}",
                userId,
                e.message,
                e,
            )
        }
    }
}
