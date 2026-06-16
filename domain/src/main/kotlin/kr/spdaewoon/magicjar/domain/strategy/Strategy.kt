package kr.spdaewoon.magicjar.domain.strategy

import java.time.Instant

/**
 * 전략 도메인 객체 — DB 영속 엔티티의 도메인 짝.
 *
 * ADR 0022 §2-4 정합. JPA 엔티티는 api 모듈 [StrategyJpaEntity].
 *
 * @param id 전략 ID (DB auto-generated)
 * @param userId 소유자 사용자 ID
 * @param definition magicJar 확장 전략 정의 (DSL inner 포함)
 * @param complexity 복잡도 메타 (등록 시 static 검증 결과)
 * @param disabledReason auto-disable 사유 (NULL = 정상)
 * @param origin 전략 출처 — 사용자 DSL vs 발굴 페이퍼 승격 (ADR 0122, add-only · default USER_DSL = 하위호환)
 * @param lastDisabledAt 마지막 auto-disable 시각
 * @param evalMetricSummary 마지막 N 평가 통계 요약 (jsonb)
 * @param createdAt 생성 시각
 * @param updatedAt 갱신 시각
 */
data class Strategy(
    val id: Long? = null,
    val userId: Long,
    val definition: StrategyDefinition,
    val complexity: StrategyComplexity,
    val disabledReason: StrategyDisabledReason = StrategyDisabledReason.NULL,
    val origin: StrategyOrigin = StrategyOrigin.USER_DSL,
    val status: StrategyStatus = StrategyStatus.ACTIVE,
    val codes: List<String> = emptyList(),
    val intervals: List<String> = emptyList(),
    val lastDisabledAt: Instant? = null,
    val evalMetricSummary: Map<String, Any>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    /** 전략이 현재 활성 상태인지 */
    fun isEnabled(): Boolean = disabledReason == StrategyDisabledReason.NULL

    /**
     * 발굴 페이퍼(MOCK) 승격 전략 여부 (ADR 0122 §2-C).
     *
     * capital 분기 식별 키 — `true`면 RiskGateway 평가가 가상 자본(`PaperCapitalSnapshotProvider`)을
     * 써야 하고 실 KIS 잔고(`CapitalSnapshotProvider`)를 호출하지 않는다(실 배선은 R2).
     */
    fun isPaperDiscovery(): Boolean = origin.isPaper()
}
