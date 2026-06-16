package kr.spdaewoon.magicjar.domain.strategy

/**
 * 전략 출처 enum — 사용자 작성 DSL vs 발굴(Discovery) 페이퍼 승격 (ADR 0122 §2-A·spec §3-R1 계약 1).
 *
 * 발굴 페이퍼 전략을 사용자 DSL 전략과 **데이터 차원에서 구분**한다([OrderSource] AUTO/MANUAL 구분 선례 정합).
 * 페이퍼 주문은 신규 [kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger] 값을 발명하지 않고
 * `STRATEGY_SIGNAL` 재사용 + mode=MOCK + `origin=PAPER_DISCOVERY` 조합으로 식별한다
 * (ADR 0122 §2-E·spec §3-R1 (a) LOCK — enum 최소화). 즉 출처 식별의 1급 키는 본 enum이다.
 *
 * ### 2가지 의도
 * - [USER_DSL] — 사용자가 StrategyBuilder로 작성한 전략 (기존 전부 · **default = 하위호환 zero-regression**)
 * - [PAPER_DISCOVERY] — 발굴 setup을 사용자 명시 opt-in으로 페이퍼(MOCK) 승격한 인스턴스
 *
 * ### 하위호환 (add-only LOCK)
 * default `USER_DSL` → 기존 모든 [Strategy] 생성 지점 무수정 컴파일 유지. `strategy` 테이블의 `origin`
 * 컬럼은 R2에서 add-only nullable로 추가되고 기존 row는 `USER_DSL`로 backfill된다(spec §3-R1).
 *
 * ### 가상 자본 격리 정합 (ADR 0122 §2-C)
 * `PAPER_DISCOVERY` 전략의 RiskGateway 평가는 가상 자본(`PaperCapitalSnapshotProvider`)을 쓴다 —
 * 실 KIS 잔고(`CapitalSnapshotProvider`)를 오염시키지 않는다. 그 capital 분기의 판정 키가 본 enum이다.
 * (분기 실 배선은 R2 — backend-core 몫.)
 *
 * // AI 호출 0 — 페이퍼는 룰 기반 시그널 경로 100% (ADR 0122 spec §4-1 · development-rules §5)
 */
enum class StrategyOrigin {
    /** 사용자가 StrategyBuilder로 작성한 전략 (기존 전부 · default). */
    USER_DSL,

    /** 발굴 setup을 사용자 opt-in으로 페이퍼(MOCK) 승격한 인스턴스. */
    PAPER_DISCOVERY,
    ;

    /** 발굴 페이퍼 승격 전략(가상 자본·MOCK 강제 경로) 여부. */
    fun isPaper(): Boolean = this == PAPER_DISCOVERY
}
