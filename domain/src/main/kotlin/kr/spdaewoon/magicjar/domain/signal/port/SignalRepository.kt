package kr.spdaewoon.magicjar.domain.signal.port

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.signal.Signal

/**
 * 시그널 영속 port (ADR 0066 §5-3 채택 + 헥사고날 아키텍처 정합).
 *
 * Phase 2 backend-core sub-agent가 `api/.../persistence/signal/SignalJpaAdapter`로 구현.
 *
 * ### 헌법 §6 정합
 * abstract fun 만 — body 가진 default fun 0건 (Spring Data JPA Repository 함정 회피).
 * 구현 클래스에서만 helper 메서드 허용 (PR-25 baseline).
 *
 * ### 멱등성 (ADR 0066 §2-5 E-1)
 * Phase 2가 DB UQ `(strategyId, code, interval, ts)` constraint 발행.
 * 구현체는 DUPLICATE_KEY 예외 발생 시 `log.debug("dup signal skipped: ...")` + skip 의무.
 *
 * spec 인용: ADR 0066 §5-3 + §7-2 단계 5.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
interface SignalRepository {
    /**
     * Signal 영속 — DB UQ 위반 시 구현체는 skip (DUPLICATE_KEY).
     *
     * @return 저장된 [Signal] (id 채워짐) 또는 중복 시 기존 [Signal] (구현체 정책)
     */
    fun save(signal: Signal): Signal

    /**
     * 멱등 검사 — 동일 boundary ts 시그널 존재 여부.
     *
     * @param strategyId 전략 ID
     * @param code 종목 코드
     * @param interval 인터벌
     * @param ts boundary closing ts
     * @return 존재 시 [Signal], 없으면 null
     */
    fun findByDedupeKey(
        strategyId: Long,
        code: String,
        interval: CandleInterval,
        ts: Instant,
    ): Signal?
}
