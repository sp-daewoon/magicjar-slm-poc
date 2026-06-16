package kr.spdaewoon.magicjar.domain.order.execution

import java.math.BigDecimal
import java.time.Instant

/**
 * 주문 의도 — `order.intent.v1` Kafka payload 도메인 모델 (ADR 0068 §14-1 LOCK · ADR 0082 T-MAN B-2-extended).
 *
 * T-RISK ALLOWED 분기 publish → OrderRouterKafkaListener consume (§14-2 step 1).
 * 본 모델은 영속 X (영속은 [OrderRequest] §14-1 정합).
 *
 * 필드 정합:
 *   id              — signal_intent_id (T-RISK ALLOWED publish 시 채번)
 *   userId          — KIS credential identity (ADR 0049 §kis-credential-identity)
 *   signalId        — T-SIG cross-ref + 멱등 키 source (의제 H-1) · MANUAL 시 0L sentinel
 *   strategyId      — strategy_id cross-ref (T-J 진입점) · MANUAL 시 0L sentinel
 *   code            — 종목코드 6자리
 *   side            — BUY/SELL ([OrderSide])
 *   priceType       — MARKET/LIMIT ([OrderPriceType])
 *   quantity        — 주문 수량 (양수 LOCK — 검증 책임은 publisher)
 *   limitPrice      — LIMIT 시 not null, MARKET 시 null
 *   idempotencyKey  — AUTO: "{signalId}:{userId}" / MANUAL: "manual:{userId}:{code}:{side}:{UUID}" (FIX B-1 P0 강화 — 동일 초 다중 주문 충돌 해소)
 *   requestedAt     — T-RISK publish 시각 (Kafka producer 채번)
 *   source          — AUTO(자동 path, default) / MANUAL(POST /api/orders/manual 진입) — ADR 0082 B-2-extended LOCK
 *   priceAtSignal   — 평가 시점 종가 (order.intent.v1 payload `priceAtSignal`). MARKET 주문 notional 산정 source
 *                     (ADR 0109 §2-5 — LIMIT는 limitPrice, MARKET은 priceAtSignal). 미발행 시 null (backward compat)
 *   orderTrigger    — 발사 출처 enum (ADR 0115 §2-P2-⑥ 키 사슬 무결성). payload `orderTrigger` 필드로 흘러
 *                     OrderRouter가 OrderRequest.orderTrigger로 영속. 미발행 시 null → source·signalId fallback 분류.
 *   triggerRefId    — 유발 근거 실키 (signal id / strategy id 등). payload `triggerRefId`. 미발행 시 null.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082 + ADR 0115)
 */
data class OrderIntent(
    val id: Long,
    val userId: String,
    val signalId: Long,
    val strategyId: Long,
    val code: String,
    val side: OrderSide,
    val priceType: OrderPriceType,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val idempotencyKey: String,
    val requestedAt: Instant,
    val source: OrderSource = OrderSource.AUTO,
    // ADR 0109 §2-5 — canary notional hard cap MARKET price source (add-only, default null = backward compat)
    val priceAtSignal: BigDecimal? = null,
    // ADR 0115 §2-P2-⑥ 키 사슬 무결성 — 발사 출처 enum + 유발 근거 실키 (add-only, default null = backward compat)
    val orderTrigger: OrderTrigger? = null,
    val triggerRefId: Long? = null,
)
