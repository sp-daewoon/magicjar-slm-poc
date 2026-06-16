package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import java.time.Instant

/**
 * OrderRequest 영속 포트 (ADR 0068 §14-1 LOCK · 헌법 §6 default fun 함정 회피 — abstract만).
 *
 * Phase 2 backend-core JPA 어댑터가 구현. 본 PR은 인터페이스만.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderRequestRepository {
    fun save(request: OrderRequest): OrderRequest

    fun findById(id: Long): OrderRequest?

    fun findByKisOrderNo(kisOrderNo: String): OrderRequest?

    fun findByIdempotencyKey(idempotencyKey: String): OrderRequest?

    fun findOpenByUserId(userId: String): List<OrderRequest>

    /**
     * KillSwitch race-safe guard B (Grace Window) 용 — userId의 마지막 ORDER_FILLED 시각.
     *
     * 정의: `status = FILLED` 중 `completedAt MAX`. 부분 체결(`PARTIALLY_FILLED`)은 grace 대상 아님 —
     *      KillSwitch 정책상 "최종 체결 직후 reconcile drift는 KIS 인덱스 반영 race"를 가드하는 목적.
     *
     * @return 한 건도 없으면 null (해당 사용자는 grace 미적용).
     */
    fun findLastFilledAt(userId: String): Instant?

    /**
     * ADR 0084 P0-2 — PENDING_RECONCILE 상태 + requestedAt < cutoff 조회.
     *
     * 사용처: [kr.spdaewoon.magicjar.consumer.order.OrderReconciliationWorker] cron 1분 — 5분 이상 경과한
     *        PENDING_RECONCILE 주문을 KIS inquire-daily-ccld로 검증.
     *
     * - status = PENDING_RECONCILE
     * - requestedAt < cutoff (즉, 너무 최근 row는 KIS 측 인덱스 미반영 위험 → 최소 grace period 부여)
     *
     * 정렬: requestedAt ASC (오래된 것 우선)
     */
    fun findByStatusAndRequestedAtBefore(
        status: OrderStatus,
        cutoff: Instant,
        limit: Int = 50,
    ): List<OrderRequest>

    /**
     * ADR 0114 §3-2 — 미체결 진입(BUY LIMIT) 취소 워커 lookup.
     *
     * 아직 종결되지 않은 BUY LIMIT 주문(`side=BUY` AND `priceType=LIMIT` AND `status IN (REQUESTED, ACK, PARTIALLY_FILLED)`)
     * 중 `requestedAt < cutoff`(= now - 최소 타임아웃) 인 후보를 oldest-first로 조회한다.
     * 행별 정확한 `entryTimeoutSec`(전략 override) 도달 여부는 워커가 추가 판정한다(cutoff는 1차 필터).
     *
     * 헌법 §6 — interface body 0건(abstract만). 구현은 JPA @Query.
     */
    fun findOpenBuyLimitBefore(
        cutoff: Instant,
        limit: Int = 50,
    ): List<OrderRequest>

    fun updateStatus(
        id: Long,
        status: OrderStatus,
        rejectReason: String?,
    ): OrderRequest?

    fun updateKisAck(
        id: Long,
        kisOrderNo: String,
        kisFwdgOrgno: String,
        acknowledgedAt: Instant,
    ): OrderRequest?

    fun markCompleted(
        id: Long,
        completedAt: Instant,
        finalStatus: OrderStatus,
    ): OrderRequest?

    /**
     * ADR 0109 GAP-3 — canary 일일 REAL 주문 횟수 전역 cap 용 카운트.
     *
     * `requestedAt`이 `[fromInclusive, toExclusive)` 범위이며 `mode = REAL`인 order_request 중
     * **side = BUY** 이고 **status <> REJECTED** 인 건수.
     * 당일 KST 경계를 caller가 UTC Instant 범위로 변환해 전달한다(단일 권위 = order_request DB · Redis 미사용,
     * ADR 0109 §2-4 drift 회피).
     *
     * ### BUY-only + REJECTED 제외 (2026-06-12 자본 보호 역전 hotfix · 최고 관리자 결재)
     * 최고 관리자 2026-06-12 직접 결재: "하루 주문 제한을 매수만 포함해야지 매도까지 세려고 만든 건 아니야.
     * 5개 제한이면 1개 종목을 사고팔면 1번 카운팅." → 왕복 1회 = 1카운트 의미로 매수(BUY)만 집계한다.
     * SELL(익절·손절·DSL 청산)은 cap 무관 — 매수 cap 도달 후 청산 주문이 거부되면 보유 포지션이 무보호가 되는
     * 자본 보호 역전(2026-06-12 라이브 incident)을 차단. REJECTED 제외 — 게이트 거부 BUY 도 row 로 남으므로
     * "체결 경로에 실제 들어간 BUY" 만 세야 거부 주문이 cap 슬롯을 잠식하지 않는다.
     *
     * 단, source=EXTERNAL(외부 HTS·MTS 직접 주문 흡수) row 는 제외한다 — 우리 자동매매 예산이 아님
     * (2026-06-05 자본 path fix). 구현 어댑터가 @Query 에 `source <> 'EXTERNAL'` 조건을 포함한다.
     *
     * 헌법 §6 — interface body 0건(abstract만). 구현은 JPA 어댑터 @Query count.
     */
    fun countRealOrdersBetween(
        fromInclusive: Instant,
        toExclusive: Instant,
    ): Long
}
