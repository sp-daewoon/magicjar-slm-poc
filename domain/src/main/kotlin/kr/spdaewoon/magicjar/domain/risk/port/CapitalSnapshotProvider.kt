package kr.spdaewoon.magicjar.domain.risk.port

import java.math.BigDecimal

/**
 * 사용자 자본(잔고) snapshot provider (ADR 0067 §5-5 + ADR 0042 정합). 헌법 §6 abstract만.
 *
 * Phase 2 어댑터는 ADR 0042 Redis cache `balance:user:{userId}` 우선 활용.
 *
 * ### F10 hotfix (2026-06-12) — [capitalOutcome] 도입, [getCapital] 의 0 fabricate 폐기
 * 조회 실패 시 0을 반환하면 호출부가 "실제 0원"과 "조회 불가"를 구분할 수 없어 14:12 incident
 * (rate limit → 0원 fabricate → POSITION_LIMIT 거짓 차단)이 발생했다. 실패를 타입으로 드러내기 위해
 * [capitalOutcome] 이 권위 진입점이 된다([CapitalOutcome.Unavailable] = 이번 시그널 스킵).
 * 레거시 [getCapital] 은 페이퍼 provider·기존 호출부 하위호환을 위해 남기되 default 로 [capitalOutcome]
 * 을 위임한다(주문경로 capital 산출은 [capitalOutcome] 만 사용).
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface CapitalSnapshotProvider {
    /**
     * 레거시 값 반환 시그니처 — 하위호환(페이퍼 가상 자본·표시용 등 "실패=값 없음 구분 불요" 호출부).
     *
     * 페이퍼 provider 는 본 시그니처를 구현(가상 자본 seed 는 조회 실패 개념이 없어 항상 값 존재).
     * 주문경로(POSITION_LIMIT 예산 산정)는 [capitalOutcome] 을 써야 한다 — 실패를 0 으로 뭉개지 않기 위함.
     */
    fun getCapital(userId: String): BigDecimal

    /**
     * 자본 산출 결과(F10 권위 진입점) — 성공값 vs 조회 불가를 [CapitalOutcome] sealed 로 구분.
     *
     * default 는 레거시 [getCapital] 을 감싸 항상 [CapitalOutcome.Available] 로 본다(예외 미발생 가정 —
     * 페이퍼 등 실패 개념 없는 provider 용). 실제 KIS 어댑터
     * ([kr.spdaewoon.magicjar.consumer.risk.KisCapitalSnapshotProviderAdapter])는 이를 override 해
     * 재시도·stale-cache·[CapitalOutcome.Unavailable] 스킵 정책을 구현한다.
     */
    fun capitalOutcome(userId: String): CapitalOutcome = CapitalOutcome.Available(getCapital(userId))
}
