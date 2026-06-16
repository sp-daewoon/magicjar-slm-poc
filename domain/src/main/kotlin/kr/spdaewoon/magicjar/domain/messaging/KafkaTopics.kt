package kr.spdaewoon.magicjar.domain.messaging

/**
 * Kafka 토픽 중앙 레지스트리 (single source of truth).
 *
 * 목적: 토픽 wire 문자열이 producer/listener/test에 raw string으로 산재해 "헷갈림"이 컸다.
 * 이 object가 유일한 wire 출처이며, 모든 producer/listener/bean/test가 이 상수를 참조한다.
 *
 * ### Phase 2 — 새 명명규칙 wire 플립 (2026-06-15)
 * Phase 1(레지스트리 중앙화) 완료 후, 이 object의 const 값을 새 명명규칙으로 교체했다.
 * 모든 참조부가 상수를 거치므로 값 교체만으로 전체 wire가 따라온다 (clean cut).
 *
 * 명명규칙: `kafka.{owner}.{kind}.{domain}.{resource}.{event}.v{version}`
 *   - owner: contract owner module (`api`, `consumer`, `batch`, `shared`)
 *   - kind: `evt`(이미 발생한 사실) | `cmd`(다른 모듈에 일을 시킴)
 *   - 출처: `docs/architecture/backup/realtime-topic-inventory.md`
 *     "Kafka Inventory & Rename Proposal" 표 (Proposed topic 컬럼)
 *
 * ### 불변식 (절대 위반 금지)
 * - [Dlt] 4종 == 각 대응 main 토픽 값 + ".DLT". (문서표의 `kafka.{owner}.dlt....v1` infix 미채택)
 *   `KafkaErrorHandlerConfig`의 `"${record.topic()}.DLT"` 동적 auto-suffix를 건드리지 않으므로
 *   DLT wire는 반드시 `<새 main 토픽>.DLT` 여야 라우팅이 작동한다.
 *
 * ### 도메인 분류 (가독성용 nested object)
 * - [Market]    : 시세·호가·시장상태·실시간수요·지수·섹터
 * - [Candle]    : 봉마감 이벤트 (1m + boundary 8종)
 * - [Order]     : 주문 의도/요청/체결/거부/취소 + 잔고변경
 * - [Risk]      : 시그널·리스크위반·킬스위치
 * - [Reconcile] : 정합 실행/드리프트/수동트리거
 * - [System]    : 시스템이벤트·KIS키변경·watchlist·종목상세
 * - [Data]      : instrument·corp_action·disclosure·news
 * - [Ai]        : AI 코멘트 요청/응답
 * - [Dlt]       : Dead Letter Topic 4종 (main + ".DLT")
 *
 * 순수 Kotlin object — Spring/JPA import 0 (domain 모듈 제약 준수).
 */
object KafkaTopics {
    /** 시세·호가·시장상태·실시간수요·지수·섹터 */
    object Market {
        const val TICK = "kafka.consumer.evt.market.tick.v1"

        const val ORDERBOOK = "kafka.consumer.evt.market.orderbook.v1"

        const val STATUS = "kafka.consumer.evt.market.status.v1"

        const val REALTIME_DEMAND = "kafka.api.cmd.market.realtime-demand.v1"

        const val INDEX_UPDATED = "kafka.batch.evt.market.index-updated.v1"

        const val SECTOR_UPDATED = "kafka.batch.evt.market.sector-updated.v1"
    }

    /** 봉마감 이벤트 (1m aggregator + boundary aggregator 8종) */
    object Candle {
        const val MINUTE_CLOSED = "kafka.consumer.evt.market.candle.1m.closed.v1"

        const val M3_CLOSED = "kafka.consumer.evt.market.candle.3m.closed.v1"

        const val M5_CLOSED = "kafka.consumer.evt.market.candle.5m.closed.v1"

        const val M15_CLOSED = "kafka.consumer.evt.market.candle.15m.closed.v1"

        const val M30_CLOSED = "kafka.consumer.evt.market.candle.30m.closed.v1"

        const val M90_CLOSED = "kafka.consumer.evt.market.candle.90m.closed.v1"

        const val H1_CLOSED = "kafka.consumer.evt.market.candle.1h.closed.v1"

        const val H4_CLOSED = "kafka.consumer.evt.market.candle.4h.closed.v1"

        const val H12_CLOSED = "kafka.consumer.evt.market.candle.12h.closed.v1"
    }

    /** 주문 의도/요청/체결/거부/취소 + 잔고변경 */
    object Order {
        const val INTENT = "kafka.shared.cmd.order.intent.v1"

        const val REQUESTED = "kafka.consumer.evt.order.requested.v1"

        const val FILLED = "kafka.consumer.evt.order.filled.v1"

        const val REJECTED = "kafka.consumer.evt.order.rejected.v1"

        const val CANCELLED = "kafka.consumer.evt.order.cancelled.v1"

        const val ACCOUNT_BALANCE_CHANGED = "kafka.consumer.evt.account.balance-changed.v1"
    }

    /** 시그널·리스크위반·킬스위치 */
    object Risk {
        const val SIGNAL_GENERATED = "kafka.consumer.evt.strategy.signal-generated.v1"

        const val VIOLATION = "kafka.consumer.evt.risk.violation.v1"

        const val KILLSWITCH_ENGAGED = "kafka.shared.evt.killswitch.engaged.v1"
    }

    /** 정합 실행/드리프트/수동트리거 */
    object Reconcile {
        const val RUN = "kafka.consumer.evt.reconcile.run.v1"

        const val DRIFT = "kafka.consumer.evt.reconcile.drift.v1"

        const val MANUAL_TRIGGER = "kafka.api.cmd.reconcile.manual-trigger.v1"
    }

    /** 시스템이벤트·KIS키변경·watchlist·종목상세 */
    object System {
        const val EVENT = "kafka.shared.evt.system.event.v1"

        const val KIS_KEY_CHANGED = "kafka.api.evt.system.kis-key.changed.v1"

        const val WATCHLIST_CHANGED = "kafka.api.evt.watchlist.changed.v1"

        const val SYMBOL_DETAIL_COMMAND = "kafka.api.cmd.symbol.detail-realtime.v1"
    }

    /** instrument·corp_action·disclosure·news */
    object Data {
        const val INSTRUMENT_UPDATED = "kafka.batch.evt.instrument.updated.v1"

        const val CORP_ACTION_APPLIED = "kafka.batch.evt.corporate-action.applied.v1"

        const val DISCLOSURE_NEW = "kafka.api.evt.disclosure.new.v1"

        const val NEWS_COLLECTED = "kafka.api.evt.news.collected.v1"
    }

    /** AI 코멘트 요청/응답 */
    object Ai {
        const val COMMENT_REQUESTED = "kafka.api.cmd.ai.comment-requested.v1"

        const val COMMENT_RESPONDED = "kafka.consumer.evt.ai.comment-responded.v1"
    }

    /**
     * Dead Letter Topic 4종.
     *
     * wire 규칙: `<새 main 토픽>.DLT` suffix (Spring Kafka DeadLetterPublishingRecoverer 기본 규칙).
     * `KafkaErrorHandlerConfig`의 `"${record.topic()}.DLT"` 동적 auto-suffix와 정합한다.
     * (문서표의 `kafka.{owner}.dlt....v1` infix는 채택하지 않음 — auto-suffix와 충돌하기 때문.)
     */
    object Dlt {
        const val ORDER_INTENT = "${Order.INTENT}.DLT"

        const val ORDER_REQUESTED = "${Order.REQUESTED}.DLT"

        const val ORDER_REJECTED = "${Order.REJECTED}.DLT"

        const val ORDER_FILLED = "${Order.FILLED}.DLT"
    }
}
