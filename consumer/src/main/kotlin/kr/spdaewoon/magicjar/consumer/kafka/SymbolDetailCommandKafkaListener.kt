package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.consumer.ingestion.OrderbookSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeDemandOwner
import kr.spdaewoon.magicjar.consumer.ingestion.RealtimeSubscriptionRouter
import kr.spdaewoon.magicjar.consumer.reconciliation.ManagedCandleUniverseCache
import kr.spdaewoon.magicjar.consumer.reconciliation.SymbolDetailMinuteGapFillService
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * symbol.detail.command.v1 Kafka consumer — 종목 상세 실시간 slot 라이프사이클 (ADR 0048 §2-1).
 *
 * payload: {"action": "ENTER"|"EXIT", "code": "005930", "userId": "...", "ts": "..."}
 * - ENTER → RealtimeSubscriptionRouter DETAIL(현재가) + OrderbookSubscriptionRouter DETAIL(호가) + universe 기록 + 분봉 gap-fill
 * - EXIT  → RealtimeSubscriptionRouter DETAIL(현재가) + OrderbookSubscriptionRouter DETAIL(호가) 즉시 해제
 *
 * ## 호가(H0UNASP0) DETAIL 경로 — 표시 중에만 복원 (2026-06-12 — anchor `detail-orderbook-live-only`)
 * 종목 상세 진입 시 호가 구독은 #939에서 전면 제거됐다가, 수동매매 시 호가가 필요하다는 최고 관리자 결재로
 * **표시 중에만** 방식으로 부분 복원됐다([OrderbookSubscriptionRouter.onSymbolDetailEntered/Exited] —
 * 진입 즉시 구독 / 이탈 즉시 해제, 옛 LRU·5분 idle 잔류 폐기). 현재가(H0UNCNT0)도 같은 DETAIL 라이프사이클을
 * 따른다. 같은 종목 STRATEGY/HOLDING/PENDING_BUY demand가 남아 있으면 각 라우터가 단일 구독을 유지한다.
 *
 * groupId: magicjar-consumer-symbol-detail
 * manual ack (MANUAL_IMMEDIATE)
 *
 * KIS-first 의무 — 현재가 slot 실제 subscribe/unsubscribe는 KisWebSocketBridge 통과.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class SymbolDetailCommandKafkaListener(
    private val objectMapper: ObjectMapper,
    private val realtimeSubscriptionRouter: RealtimeSubscriptionRouter,
    private val orderbookSubscriptionRouter: OrderbookSubscriptionRouter,
    private val universeCache: ManagedCandleUniverseCache,
    private val minuteGapFillService: SymbolDetailMinuteGapFillService,
) {
    private val log = LoggerFactory.getLogger(SymbolDetailCommandKafkaListener::class.java)

    @KafkaListener(
        topics = [KafkaTopics.System.SYMBOL_DETAIL_COMMAND],
        groupId = "magicjar-consumer-symbol-detail",
        containerFactory = "manualImmediateKafkaListenerContainerFactory",
    )
    fun onMessage(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val action =
                payload["action"]?.toString() ?: run {
                    ack.acknowledge()
                    return
                }
            val code =
                payload["code"]?.toString() ?: run {
                    ack.acknowledge()
                    return
                }
            val userId = payload["userId"]?.toString() ?: "unknown"
            // bare ownerId — prefix(`detail:`)는 스냅샷 직렬화에서 1회 부여(이중 prefix `detail:detail:<id>` 회피).
            // ENTER/EXIT 동일 값(userId)을 써 멱등 release 매칭 유지.
            val ownerId = userId

            log.info("symbol.detail.command 수신: action={} code={}", action, code)

            when (action) {
                "ENTER" -> {
                    realtimeSubscriptionRouter.request(
                        owner = RealtimeDemandOwner.DETAIL,
                        ownerId = ownerId,
                        code = code,
                    )
                    // 호가(H0UNASP0) — 상세 표시 중에만 구독 (anchor `detail-orderbook-live-only`).
                    orderbookSubscriptionRouter.onSymbolDetailEntered(code)
                    universeCache.recordDetail(code)
                    minuteGapFillService.submitIfNeeded(code)
                }
                "EXIT" -> {
                    realtimeSubscriptionRouter.release(
                        owner = RealtimeDemandOwner.DETAIL,
                        ownerId = ownerId,
                        code = code,
                    )
                    // 호가 — 이탈 즉시 해제(STRATEGY demand 남으면 단일 구독 유지).
                    orderbookSubscriptionRouter.onSymbolDetailExited(code)
                }
                else -> log.warn("알 수 없는 action: {} — 무시", action)
            }
            ack.acknowledge()
        } catch (e: Exception) {
            log.error(
                "symbol.detail.command.v1 처리 실패 — offset={} err={}",
                record.offset(),
                e.message,
                e,
            )
            // 파싱/처리 오류 시 ack (orderbook 명령은 재처리 의미 없음 — 이미 페이지 전환됨)
            ack.acknowledge()
        }
    }
}
