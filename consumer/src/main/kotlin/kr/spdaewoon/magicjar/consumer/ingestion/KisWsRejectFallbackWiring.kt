package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.consumer.reconciliation.SymbolDetailMinuteGapFillService
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * KIS WS OPSP0008(MAX SUBSCRIBE OVER) 거부 시 graceful fallback wiring (ADR 0123 §3-4 R1).
 *
 * [KisWebSocketBridge.onSubscribeRejected] 콜백을 등록해, 슬롯 한도(40) 초과로 거부된 종목을
 * 분봉 gap-fill + quote polling으로 보완한다(분봉 연속성 보장 — 후성 093370 결함 직접 해소).
 *
 * ## 흐름 (거부된 (code, trId) 1건당)
 * 1. **분봉 gap-fill** — H0UNCNT0(체결가) 거부 시
 *    `SymbolDetailMinuteGapFillService.submitIfNeeded(code)` 호출로 WS 미수신 구간 1m 분봉을 KIS REST로 보완.
 *    호가(H0UNASP0)·시간외(H0STOUP0) 거부는 분봉 영향 없어 skip.
 * 2. **watchlist pool 등록** — `WatchlistPollingWorker.add(code)` (pool 추적만).
 *    ★ 2026-06-15 ADR 0124 amend: watchlist 시세 5초 REST polling 은 KIS quota 보호를 위해 폐지됐다.
 *    따라서 WS 거부 시 시세 polling 전환은 더 이상 일어나지 않는다(pool 등록은 무해 NOOP). 분봉 연속성은
 *    위 gap-fill 이 단독 보장한다.
 *
 * ## 헌법 §6 + onReconnect 패턴 정합
 * KisWebSocketBridge가 reconciliation/polling 컴포넌트를 직접 의존하지 않도록(헌법 §6 ObjectProvider) 콜백으로 분리.
 * 본 wiring은 ApplicationReadyEvent(부팅 완료 후)에서 hook을 register — KisExecutionBackfillService.onReconnect 패턴 동일.
 * deps는 ObjectProvider로 graceful(qa fixture·부재 환경 부팅 보장).
 *
 * ## R1 범위
 * 거부 시 fallback만 — 선제 eviction(R2)·채널 다이어트(R3)는 별도 라운드.
 */
@Component
class KisWsRejectFallbackWiring(
    private val webSocketBridge: KisWebSocketBridge,
    private val gapFillServiceProvider: ObjectProvider<SymbolDetailMinuteGapFillService>,
    private val pollingWorkerProvider: ObjectProvider<WatchlistPollingWorker>,
) {
    private val log = LoggerFactory.getLogger(KisWsRejectFallbackWiring::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun registerOnSubscribeRejected() {
        webSocketBridge.onSubscribeRejected = { code, trId -> handleReject(code, trId) }
        log.info("ADR 0123 R1 — KIS WS OPSP0008 거부 fallback hook register 완료 (gap-fill + polling)")
    }

    /**
     * 거부 1건 graceful 보완. WS thread에서 호출되므로 예외를 가두고(cascade 차단) 분봉·polling을 보완한다.
     */
    internal fun handleReject(
        code: String,
        trId: String,
    ) {
        try {
            // 체결가 채널 거부 → 분봉 연속성 risk → gap-fill 트리거. 호가·시간외는 분봉 미영향(skip).
            val isTickReject = trId == "H0UNCNT0"
            if (isTickReject) {
                gapFillServiceProvider.ifAvailable?.submitIfNeeded(code = code, reason = "ws-opsp0008-reject")
            }
            // watchlist pool 등록(추적용). ADR 0124 amend(2026-06-15): 시세 5초 polling 폐지로 NOOP.
            pollingWorkerProvider.ifAvailable?.add(code)
            log.info(
                "ADR 0123 R1 — OPSP0008 거부 fallback 적용: code={} trId={} (gap-fill={} polling=폐지·pool-add)",
                code,
                trId,
                isTickReject,
            )
        } catch (e: Exception) {
            log.error(
                "ADR 0123 R1 — OPSP0008 fallback 실패(분봉 연속성 risk): code={} trId={} err={}",
                code,
                trId,
                e.message,
                e,
            )
        }
    }
}
