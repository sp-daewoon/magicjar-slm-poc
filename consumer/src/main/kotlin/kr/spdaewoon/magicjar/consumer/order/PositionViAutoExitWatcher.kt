package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
import kr.spdaewoon.magicjar.domain.strategy.ViActionPolicy
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * H0UNMKO0 VI 이벤트 기반 보유 포지션 정책 watcher.
 *
 * 정책 매트릭스:
 * - EXIT_MARKET: VI 활성 이벤트 확인 시 같은 전략 보유분 MARKET SELL.
 * - ALERT_ONLY: SystemEvent 알림만 남기고 주문하지 않음.
 * - HOLD: 구독 대상도 주문 대상도 아님.
 *
 * 자동청산은 "VI 활성"으로 판정된 이벤트에서만 진행한다. VI 해제/비활성 이벤트는 주문 경로로 넘기지 않는다.
 */
@Component
class PositionViAutoExitWatcher(
    private val objectMapper: ObjectMapper,
    private val holdingPositionIndex: HoldingPositionIndex,
    private val orderRequestRepositoryProvider: ObjectProvider<OrderRequestRepository>,
    private val restingSellOrderCanceller: RestingSellOrderCanceller,
    private val orderIntentKafkaProducer: OrderIntentKafkaProducer,
    private val positionJpaRepository: PositionJpaRepository,
    private val systemEventLogger: SystemEventLogger,
    @Value("\${magicjar.autotrading.vi-auto-exit.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${magicjar.autotrading.vi-auto-exit.cooldown-seconds:30}")
    private val cooldownSeconds: Long = 30,
    @Value("\${magicjar.mode:MOCK}")
    private val magicjarMode: String = "MOCK",
) {
    private val log = LoggerFactory.getLogger(PositionViAutoExitWatcher::class.java)
    private val intentCooldown = ConcurrentHashMap<String, FiredIntent>()
    private val alertCooldown = ConcurrentHashMap<String, Long>()

    @KafkaListener(topics = [KafkaTopics.Market.STATUS], groupId = GROUP)
    fun onMarketStatus(record: ConsumerRecord<String, String>) {
        if (!enabled) return
        val event = parse(record) ?: return
        if (!event.isViActive) return

        val observed =
            holdingPositionIndex
                .heldBy(event.code)
                .filter { it.viActionPolicy != ViActionPolicy.HOLD }
        if (observed.isEmpty()) return

        emitViAlert(event, observed)

        observed
            .filter { it.viActionPolicy == ViActionPolicy.EXIT_MARKET }
            .forEach { position ->
                runCatching { evaluate(position, event) }
                    .onFailure { e ->
                        log.warn(
                            "VI auto-exit 평가 실패(다음 이벤트 재평가): userId={} strategyId={} code={} err={}",
                            position.userId,
                            position.strategyId,
                            position.code,
                            e.message,
                        )
                    }
            }
    }

    internal fun evaluate(
        position: HoldingPositionIndex.HeldPosition,
        event: MarketStatusPayload,
    ) {
        if (position.viActionPolicy != ViActionPolicy.EXIT_MARKET) return
        if (isCoolingDown(position, event)) {
            log.debug(
                "VI auto-exit cooldown skip: userId={} strategyId={} code={} qty={}",
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
            )
            return
        }
        if (!hasAuthorityHolding(position)) {
            log.warn(
                "VI auto-exit skip(유령 포지션 — position 권위 잔량 0): userId={} strategyId={} code={} ledgerQty={}",
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
            )
            return
        }

        val orderRepo = orderRequestRepositoryProvider.getIfAvailable()
        if (orderRepo == null) {
            log.error(
                "VI auto-exit fail-closed: OrderRequestRepository 미합류 — 청산 보류 userId={} strategyId={} code={}",
                position.userId,
                position.strategyId,
                position.code,
            )
            return
        }

        val cancelResult =
            restingSellOrderCanceller.cancelRestingSells(
                orderRepo = orderRepo,
                userId = position.userId,
                strategyId = position.strategyId,
                code = position.code,
                excludeIdempotencyPrefix = AutoOrderIdempotencyKeys.PREFIX_VI_AUTO_EXIT,
            )
        if (!cancelResult.canProceed) return

        val idempotencyKey =
            AutoOrderIdempotencyKeys.viAutoExit(
                position.userId,
                position.strategyId,
                position.code,
                position.quantity,
                position.updatedAtEpoch,
                event.viClassCode ?: "",
                event.overtimeViClassCode ?: "",
            )

        orderIntentKafkaProducer.publishExitIntent(
            userId = position.userId,
            strategyId = position.strategyId,
            code = position.code,
            intervalCode = "1m",
            side = OrderSide.SELL,
            priceType = OrderPriceType.MARKET,
            quantity = position.quantity,
            limitPrice = null,
            priceAtSignal = position.avgPrice.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE,
            idempotencyKey = idempotencyKey,
            orderTrigger = OrderTrigger.VI_AUTO_EXIT,
            triggerRefId = position.strategyId,
        )
        intentCooldown[cooldownKey(position)] =
            FiredIntent(firedAtEpoch = Instant.now().epochSecond, stateSignature = stateSignatureOf(position, event))
        log.warn(
            "VI auto-exit MARKET intent: userId={} strategyId={} code={} viCls={} ovtmViCls={} qty={} idemKey={}",
            position.userId,
            position.strategyId,
            position.code,
            event.viClassCode,
            event.overtimeViClassCode,
            position.quantity,
            idempotencyKey,
        )
    }

    private fun emitViAlert(
        event: MarketStatusPayload,
        positions: List<HoldingPositionIndex.HeldPosition>,
    ) {
        val key = "${event.code}:${event.viClassCode}:${event.overtimeViClassCode}"
        val now = Instant.now().epochSecond
        val last = alertCooldown[key]
        if (last != null && now - last < ALERT_COOLDOWN_SECONDS) return
        alertCooldown[key] = now

        val policies =
            positions
                .groupingBy { it.viActionPolicy.name }
                .eachCount()
        val userIds = positions.map { it.userId }.distinct()
        val detail =
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to event.code,
                    "viClassCode" to event.viClassCode,
                    "overtimeViClassCode" to event.overtimeViClassCode,
                    "exchangeClassCode" to event.exchangeClassCode,
                    "policies" to policies,
                    "strategyIds" to positions.map { it.strategyId }.distinct(),
                    "sourceTrId" to event.sourceTrId,
                    "ts" to event.ts,
                ),
            )
        runCatching {
            systemEventLogger.emit(
                SystemEvent(
                    category = SystemEventCategory.OTHER,
                    severity = SystemEventSeverity.WARN,
                    actor = "PositionViAutoExitWatcher",
                    action = "KIS_VI_TRIGGERED",
                    userId = userIds.singleOrNull(),
                    correlationId = "vi:${event.code}:${event.viClassCode ?: ""}:${event.overtimeViClassCode ?: ""}",
                    detailJson = detail,
                ),
            )
        }.onFailure { e ->
            log.warn("VI SystemEvent emit 실패(주문 흐름은 계속): code={} err={}", event.code, e.message)
        }
    }

    private fun isCoolingDown(
        position: HoldingPositionIndex.HeldPosition,
        event: MarketStatusPayload,
    ): Boolean {
        val fired = intentCooldown[cooldownKey(position)] ?: return false
        if (fired.stateSignature != stateSignatureOf(position, event)) return false
        return Instant.now().epochSecond - fired.firedAtEpoch < cooldownSeconds
    }

    private fun hasAuthorityHolding(position: HoldingPositionIndex.HeldPosition): Boolean =
        runCatching {
            val source = RuntimeModeParser.tradingModeOrMock(magicjarMode).name
            (positionJpaRepository.findByUserIdAndCodeAndSource(position.userId, position.code, source)?.quantity ?: 0) > 0
        }.getOrElse { e ->
            log.warn(
                "VI auto-exit position 권위 조회 실패 — fail-safe 발사 진행: userId={} code={} err={}",
                position.userId,
                position.code,
                e.message,
            )
            true
        }

    private fun cooldownKey(position: HoldingPositionIndex.HeldPosition): String =
        "${position.userId}:${position.strategyId}:${position.code}"

    private fun stateSignatureOf(
        position: HoldingPositionIndex.HeldPosition,
        event: MarketStatusPayload,
    ): String = "${position.quantity}:${position.updatedAtEpoch}:${event.viClassCode}:${event.overtimeViClassCode}"

    private fun parse(record: ConsumerRecord<String, String>): MarketStatusPayload? {
        return try {
            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            MarketStatusPayload(
                code = raw["code"]?.toString() ?: return null,
                isViActive = raw.booleanOrFalse("isViActive"),
                viClassCode = raw["viClassCode"]?.toString()?.takeIf { it.isNotBlank() },
                overtimeViClassCode = raw["overtimeViClassCode"]?.toString()?.takeIf { it.isNotBlank() },
                exchangeClassCode = raw["exchangeClassCode"]?.toString()?.takeIf { it.isNotBlank() },
                sourceTrId = raw["sourceTrId"]?.toString() ?: "H0UNMKO0",
                ts = raw["ts"]?.toString(),
            )
        } catch (e: Exception) {
            log.warn("PositionViAutoExitWatcher market.status parse skip: offset={} err={}", record.offset(), e.message)
            null
        }
    }

    private fun Map<String, Any?>.booleanOrFalse(key: String): Boolean =
        when (val raw = this[key]) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> false
        }

    data class MarketStatusPayload(
        val code: String,
        val isViActive: Boolean,
        val viClassCode: String?,
        val overtimeViClassCode: String?,
        val exchangeClassCode: String?,
        val sourceTrId: String,
        val ts: String?,
    )

    private data class FiredIntent(
        val firedAtEpoch: Long,
        val stateSignature: String,
    )

    companion object {
        const val GROUP = "magicjar-consumer-position-vi-auto-exit"
        private const val ALERT_COOLDOWN_SECONDS = 60L
    }
}
