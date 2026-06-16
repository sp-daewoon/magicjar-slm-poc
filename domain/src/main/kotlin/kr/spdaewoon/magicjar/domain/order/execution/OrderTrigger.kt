package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 발사 출처 — 키 사슬 무결성(traceability chain) 1급 키 (ADR 0115 §2-P2-⑥ · spec §2-B-2 불변식 1).
 *
 * 최고 관리자 직접 요구(2026-06-10): *"전략이 사인 내면 몇 주 주문 내고, 몇 주가 그 전략으로 사지고
 * 팔아졌는지를 키로 매핑 관리해야 한다. 매도했다는데 포지션이 남고 매수했다는데 포지션이 안 보이면
 * 자동매매 시스템 실격."*
 *
 * ### 왜 필요한가 — 0-sentinel 폐지 (spec §2-B-1 4 단절)
 *
 * 현행은 자동청산 SELL이 `signal_id=0` sentinel, 백필 stub이 `strategy_id=0` sentinel로 발사 출처를
 * **데이터 차원에서 소실**한다. sentinel(`0`)은 "키 없음을 키 값으로 위장"하는 안티패턴이며, 그 결과
 * `LedgerAttributedDriftCorrection`이 부족 방향 drift를 귀속 판정할 때 "이 체결이 어느 전략 것인가"가
 * 데이터에서 모호해 WARN-only로 빠진다(G7 "귀속 모호"의 근본 원인 = 키 부재).
 *
 * **OrderTrigger는 "signal이 없는 주문"은 허용하되 "출처를 모르는 주문"은 폐지한다.** AUTO_EXIT·STOP_LOSS도
 * 유발 근거(청산 대상 strategy/position id·발동 룰 id)를 [OrderRequest.triggerRefId] 실키로 보존한다.
 *
 * ### 분류 매트릭스 (발사 지점 → trigger)
 *
 * | trigger | 발사 지점 | trigger_ref_id |
 * |---|---|---|
 * | [STRATEGY_SIGNAL]      | `SignalGeneratedKafkaListener` → 시그널 파생 주문 | signal id |
 * | [AUTO_EXIT]            | `AutoExitOrderCoordinator` 익절 선주문(takeProfitPct 전량) | strategy id |
 * | [PARTIAL_TAKE_PROFIT]  | 분할 익절(다단계 stage) 청산 — stage 발화 | strategy id (stage는 audit) |
 * | [STOP_LOSS]            | `PositionStopLossTickWatcher` tick 손절 | strategy id |
 * | [VI_AUTO_EXIT]         | `PositionViAutoExitWatcher` H0UNMKO0 VI 발동 자동청산 | strategy id |
 * | [MANUAL]               | `ManualOrderService`(POST /api/orders/manual) | null |
 * | [EXTERNAL]             | KIS HTS·MTS 외부 주문을 WS·REST backfill이 흡수 | null |
 *
 * ### source([OrderSource])와의 관계 — 직교
 *
 * [OrderSource]는 *우리가 발의했는가*(AUTO/MANUAL/EXTERNAL)를, [OrderTrigger]는 *무엇이 방아쇠인가*를 표현한다.
 * 자동청산(AUTO_EXIT·STOP_LOSS·PARTIAL_TAKE_PROFIT)은 source=AUTO + trigger 세분화. MANUAL·EXTERNAL은 1:1.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0115 §2-P2-⑥)
 */
enum class OrderTrigger {
    /** 전략 시그널 파생 주문 — trigger_ref_id = signal id. */
    STRATEGY_SIGNAL,

    /** 자동 익절(전량 takeProfitPct) 선주문 — trigger_ref_id = strategy id. */
    AUTO_EXIT,

    /** 손절(stop-loss) tick watcher 발사 — trigger_ref_id = strategy id. */
    STOP_LOSS,

    /** VI 발동 자동청산 — trigger_ref_id = strategy id. */
    VI_AUTO_EXIT,

    /** 분할 익절(다단계 stage) 청산 — trigger_ref_id = strategy id. */
    PARTIAL_TAKE_PROFIT,

    /** 사용자 수동 주문(POST /api/orders/manual). */
    MANUAL,

    /** magicJar 외부(KIS HTS·MTS·타 client) 주문을 체결통보·REST backfill이 흡수. */
    EXTERNAL,
}
