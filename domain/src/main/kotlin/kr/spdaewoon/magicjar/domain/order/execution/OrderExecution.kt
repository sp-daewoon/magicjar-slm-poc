package kr.spdaewoon.magicjar.domain.order.execution

import java.math.BigDecimal
import java.time.Instant

/**
 * 분할 체결 1건 도메인 (ADR 0068 §14-1 LOCK · §6-2 order_execution 테이블 정합).
 *
 * KisExecutionWsListener H0STCNI0 CNTG_YN="2" 수신마다 INSERT.
 * 누적 합 [OrderExecutionRepository.sumExecutedQuantityByOrder] 으로 PARTIALLY_FILLED → FILLED 전이 판정.
 *
 * 필드 LOCK (§14-6 26 컬럼 매핑 표 §14-1 정합):
 *   id                 — bigserial PK
 *   orderId            — order_request.id FK
 *   userId             — credential identity
 *   kisOrderNo         — KIS ODNO (cross-ref)
 *   executedQuantity   — CNTG_QTY
 *   executedPrice      — CNTG_UNPR
 *   executedAt         — STCK_CNTG_HOUR (HHMMSS) + today date 합성
 *   fee                — KIS 별도 조회 의무, 1차 null 허용
 *   tax                — 매도세·증권거래세, 1차 null 허용
 *   eventId            — ADR 0084 P0-3 — WS + REST backfill 멱등 키 ({kisOrderNo}:{HHMMSS}:{qty}:{price})
 *   createdAt          — listener INSERT 시각
 *
 * ### eventId 멱등성 LOCK (ADR 0084 P0-3)
 * Flyway V35 — order_execution.event_id VARCHAR(64) + UNIQUE partial index.
 * 동일 체결이 WS 통보 + REST inquire-daily-ccld 양쪽에서 도달해도 UNIQUE constraint가 중복 INSERT 차단.
 * V21 시점 기존 row는 null 허용 (add-only 정합).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0084 §2-8)
 */
data class OrderExecution(
    val id: Long? = null,
    val orderId: Long,
    val userId: String,
    val kisOrderNo: String,
    val executedQuantity: Int,
    val executedPrice: BigDecimal,
    val executedAt: Instant,
    val fee: BigDecimal? = null,
    val tax: BigDecimal? = null,
    val eventId: String? = null,
    val createdAt: Instant,
)
