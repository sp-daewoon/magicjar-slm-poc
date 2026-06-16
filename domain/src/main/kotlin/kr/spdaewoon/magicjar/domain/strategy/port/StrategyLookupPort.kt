package kr.spdaewoon.magicjar.domain.strategy.port

import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.strategy.Strategy

/**
 * 실시간 시그널 평가용 전략 조회 port (ADR 0066 §5-3 채택).
 *
 * RealtimeSignalGenerator가 boundary 마감 payload 수신 시
 * `findActiveByCodeAndInterval(code, interval)` 호출로 평가 가능(status=ACTIVE 또는 BUY_BLOCKED) 전략 목록 조회.
 *
 * ### 기존 [StrategyRepositoryPort]와 분리
 * 기존 port는 사용자 CRUD 본체 (save · findById · findAllByUserId · deleteById · autoDisable · enable).
 * 본 port는 실시간 시그널 평가 hot path 전용 — Phase 2 backend-core가 Redis cache 적용 구현.
 *
 * ### 헌법 §6 정합
 * abstract fun 만 — body 가진 default fun 0건.
 *
 * ### Phase 2 영속
 * Phase 2 backend-core sub-agent가 `api/.../persistence/strategy/StrategyLookupJpaAdapter`로 구현.
 * Phase 1은 stub bean 또는 구현 0건 (RealtimeSignalGenerator 의존 인터페이스만 노출).
 *
 * spec 인용: ADR 0066 §5-3 + §7-2 단계 1.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
interface StrategyLookupPort {
    /**
     * 실시간 시그널 평가 — 종목·인터벌 매칭 + 평가 가능 전략 목록 조회.
     *
     * @param code 종목 코드 (KIS 6자리)
     * @param interval 평가 인터벌 (1m·3m·5m·15m·30m·90m·1h·4h·12h 등)
     * @return 평가 가능 전략 목록 (status=ACTIVE 또는 BUY_BLOCKED)
     */
    fun findActiveByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy>

    /**
     * ADR 0114 §4-2 P2 — 청산(SELL) 평가 대상 전략 (position-driven 폴백).
     *
     * [findActiveByCodeAndInterval] 결과(ACTIVE/BUY_BLOCKED) **∪ DISABLED-with-position** 합집합.
     * "전략 status와 무관하게 보유분이 있으면 청산 평가"(불변식 전환). BUY는 여전히 ACTIVE만 — 평가기가
     * DISABLED·BUY_BLOCKED 의 BUY를 suppress한다. 보유 0인 DISABLED는 포함하지 않는다(과도 평가 회피).
     *
     * default 구현: 하위호환 — 미구현 어댑터(stub)는 [findActiveByCodeAndInterval]로 위임(DISABLED 폴백 없이 현행).
     * 본 인터페이스는 JPA Repository가 아니라 도메인 port이므로 default 허용(헌법 §6 query-inference 함정 무관).
     */
    fun findSellEvalByCodeAndInterval(
        code: String,
        interval: CandleInterval,
    ): List<Strategy> = findActiveByCodeAndInterval(code, interval)
}
