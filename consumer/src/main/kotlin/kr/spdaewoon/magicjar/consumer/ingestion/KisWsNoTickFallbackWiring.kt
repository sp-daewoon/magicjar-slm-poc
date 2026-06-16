package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.reconciliation.NoTickMinuteFallbackService
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * H0UNCNT0 SUBSCRIBE SUCCESS 후 체결 tick 0건 silent fallback wiring.
 *
 * OPSP0008 reject는 [KisWsRejectFallbackWiring]이 처리하지만, KIS가 SUCCESS를 준 뒤 특정 종목 체결 프레임을
 * 보내지 않는 케이스는 reject hook을 타지 않는다. 이 wiring은 no-tick watchdog hook을 받아 1m REST 보완을
 * 저빈도로 큐잉한다.
 */
@Component
class KisWsNoTickFallbackWiring(
    private val webSocketBridge: KisWebSocketBridge,
    private val fallbackServiceProvider: ObjectProvider<NoTickMinuteFallbackService>,
) {
    private val log = LoggerFactory.getLogger(KisWsNoTickFallbackWiring::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun registerOnNoTickDetected() {
        webSocketBridge.onNoTickDetected = { code, trId, ageSeconds ->
            if (trId == KisWebSocketBridge.TR_ID_UNIFIED_TICK) {
                fallbackServiceProvider.ifAvailable?.submit(code = code, ageSeconds = ageSeconds)
            }
        }
        log.info("KIS WS 무tick fallback hook register 완료 (H0UNCNT0 silent → 1m REST 보완)")
    }
}
