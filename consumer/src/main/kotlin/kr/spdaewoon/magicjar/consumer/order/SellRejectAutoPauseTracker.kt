package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SELL 거부 N회 누적 시 전략·종목 자동 정지 — ADR 0115 §P2-2 (R2 배선).
 *
 * 2026-06-08 incident — strategy_position 유령 수량(7 vs position 5)이 매도 거부를 무한 유발했다.
 * P1-1 drift 자동보정이 정합을 회복하지만, 그 직전·경합 구간에 거부가 누적되면 자원·알림이 소모된다.
 * 본 tracker는 **같은 전략·종목의 연속 SELL 거부**를 인메모리로 세고, 임계([rejectAutoPauseThreshold], default 10)
 * 도달 시 해당 전략을 자동 정지([ConsumerStrategyJpaRepository.autoDisable])하고 1회만 알림을 emit한다.
 *
 * ## 자연 해제 (ADR 0115 §P2-2)
 * 정지는 P1-1 drift 자동보정이 정합을 회복하면 다음 신호가 정상화되어 자연 해소되며, 사용자가 전략을 재활성화한다.
 * 자동보정과 자동정지가 함께 작동해 incident 의 "거부 폭주 + 알림 폭주"를 양쪽에서 차단한다(ADR 0115 §P2-2).
 *
 * ## 디바운스 (NotificationRateLimiterPort 재사용)
 * 정지 알림은 [NotificationRateLimiterPort.shouldEmitOncePerWindow]로 윈도 1회만(반복 정지 알림 폭주 회피).
 * limiter 미등록(graceful)이면 알림 1회 emit 후 카운터만 reset(인메모리 1회성 보장).
 *
 * ## 상태 기반 reset
 * - BUY 또는 SELL **성공/체결** 시 해당 키 카운터 reset([resetOnSuccess]) — 연속 거부만 집계.
 * - 정지 발동 시에도 카운터 reset(중복 autoDisable·중복 알림 회피).
 *
 * consumer 단일 인스턴스 가정(현 배포 — P2-1 cooldown 정합). 다중 인스턴스 확장 시 Redis 승격(P2-3 backlog).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 운영 가드 (ADR 0023 §2-4)
 */
@Component
class SellRejectAutoPauseTracker(
    private val strategyRepositoryProvider: ObjectProvider<ConsumerStrategyJpaRepository>,
    private val rateLimiterProvider: ObjectProvider<NotificationRateLimiterPort>,
    private val systemEventLoggerProvider: ObjectProvider<SystemEventLogger>,
    @Value("\${magicjar.notification.reject-auto-pause.threshold:10}")
    private val rejectAutoPauseThreshold: Int = 10,
) {
    private val log = LoggerFactory.getLogger(SellRejectAutoPauseTracker::class.java)

    /** key = userId:strategyId:code → 연속 SELL 거부 횟수(인메모리). 성공/체결·정지 발동 시 reset. */
    private val rejectCounters = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * 주문 거부 1건 기록 — SELL 이고 임계 도달이면 전략 자동 정지 + 1회 알림.
     * BUY 거부·sentinel(strategyId<=0)은 집계 대상 아님(전략 귀속 없음 — MANUAL).
     */
    fun onOrderRejected(order: OrderRequest) {
        if (order.side != OrderSide.SELL || order.strategyId <= 0L || rejectAutoPauseThreshold <= 0) return

        val key = keyOf(order.userId, order.strategyId, order.code)
        val count = rejectCounters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        log.warn(
            "SELL reject 누적: userId={} strategyId={} code={} count={}/{} (임계 도달 시 자동 정지)",
            maskUserId(order.userId),
            order.strategyId,
            order.code,
            count,
            rejectAutoPauseThreshold,
        )
        if (count >= rejectAutoPauseThreshold) {
            autoPause(order, count)
            rejectCounters.remove(key) // 중복 autoDisable·알림 회피(정지 후 reset).
        }
    }

    /** SELL/BUY 성공·체결 시 해당 전략·종목 연속 거부 카운터 reset(상태 진전 — 연속 거부만 집계). */
    fun resetOnSuccess(order: OrderRequest) {
        if (order.strategyId <= 0L) return
        rejectCounters.remove(keyOf(order.userId, order.strategyId, order.code))
    }

    private fun autoPause(
        order: OrderRequest,
        count: Int,
    ) {
        val reason = "AUTO_PAUSE_SELL_REJECT_x$count(code=${order.code})"
        val repo = strategyRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.error(
                "SELL reject 자동정지 skip — ConsumerStrategyJpaRepository 미합류: userId={} strategyId={} code={}",
                maskUserId(order.userId),
                order.strategyId,
                order.code,
            )
            return
        }
        val updated =
            runCatching { repo.autoDisable(order.strategyId, reason) }
                .onFailure { e ->
                    log.error(
                        "SELL reject 자동정지 autoDisable 실패: strategyId={} err={}",
                        order.strategyId,
                        e.message,
                    )
                }.getOrDefault(0)
        log.warn(
            "STRATEGY_AUTO_PAUSED(SELL reject x{}): userId={} strategyId={} code={} updatedRows={} reason={}",
            count,
            maskUserId(order.userId),
            order.strategyId,
            order.code,
            updated,
            reason,
        )
        emitPauseAlertOnce(order, count)
    }

    /** 정지 알림 1회 — rate limiter once-per-window 디바운스(반복 정지 알림 폭주 회피). limiter 부재 시 1회 emit. */
    private fun emitPauseAlertOnce(
        order: OrderRequest,
        count: Int,
    ) {
        val limiter = rateLimiterProvider.getIfAvailable()
        val cooldownKey = "auto-pause.sell-reject.${order.strategyId}.${order.code}"
        val allowed = limiter?.shouldEmitOncePerWindow(order.userId, cooldownKey, ALERT_WINDOW_MIN) ?: true
        if (!allowed) return

        systemEventLoggerProvider.ifAvailable?.let { logger ->
            runCatching {
                logger.emit(
                    SystemEvent(
                        category = SystemEventCategory.RECONCILE,
                        severity = SystemEventSeverity.WARN,
                        actor = ACTOR,
                        action = "STRATEGY_AUTO_PAUSED_SELL_REJECT",
                        userId = order.userId,
                        detailJson =
                            """{"strategyId":${order.strategyId},"code":"${order.code}",""" +
                                """"rejectCount":$count,"threshold":$rejectAutoPauseThreshold}""",
                    ),
                )
            }.onFailure { e ->
                log.error("SystemEventLogger.emit 실패: action=STRATEGY_AUTO_PAUSED_SELL_REJECT err={}", e.message)
            }
        }
    }

    private fun keyOf(
        userId: String,
        strategyId: Long,
        code: String,
    ): String = "$userId:$strategyId:$code"

    private fun maskUserId(userId: String): String = if (userId.length >= 4) userId.take(4) + "****" else "****"

    private companion object {
        const val ACTOR = "SellRejectAutoPauseTracker"
        const val ALERT_WINDOW_MIN = 60
    }
}
