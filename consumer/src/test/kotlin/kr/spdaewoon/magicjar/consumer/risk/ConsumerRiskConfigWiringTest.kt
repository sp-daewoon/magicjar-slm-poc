package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.consumer.config.JpaScanConfiguration
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.persistence.shared.risk.RiskConfigJpaAdapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.stereotype.Component

/**
 * 2026-06-10 incident 회귀 가드 — consumer ApplicationContext가 RiskConfigRepository 빈을 발견하는지 정적 검증.
 *
 * ## 배경 (incident)
 * `RiskConfigJpaAdapter`가 api 모듈에만 존재해 consumer에는 빈이 없었다. consumer
 * `SignalGeneratedKafkaListener`의 `ObjectProvider<RiskConfigRepository>.getIfAvailable()`이 항상 null →
 * 사용자 DB risk_config(예: 5M/70%)를 무시하고 RiskConfig.defaultFor(1M/10%)만 평가하는 silent 자본 결함.
 *
 * ## Fix — ADR 0088 패턴
 * 어댑터·엔티티·리포지터리를 persistence-shared(`kr.spdaewoon.magicjar.persistence.shared.risk`)로 이동.
 * persistence-shared scan 경로는 api·consumer 양쪽 [JpaScanConfiguration] basePackages에 이미 포함 → 빈 공유.
 *
 * ## 본 테스트 — 부팅 없이 배선 불변식 단언 (DB 컨테이너 0)
 *   1. `RiskConfigJpaAdapter`는 `@Component` (ComponentScan 발견 대상).
 *   2. `RiskConfigJpaAdapter`는 `RiskConfigRepository` port 구현 (ObjectProvider 주입 타입 정합).
 *   3. 어댑터 패키지가 consumer `JpaScanConfiguration`의 `@EnableJpaRepositories`/`@EntityScan` basePackages
 *      중 하나의 prefix에 포함 → JpaRepository·@Entity 발견 보장.
 *
 * // AI 호출 0 — T-RISK 트랙 정책.
 */
class ConsumerRiskConfigWiringTest {
    @Test
    fun `RiskConfigJpaAdapter는 RiskConfigRepository port를 구현한 @Component다`() {
        assertThat(RiskConfigRepository::class.java.isAssignableFrom(RiskConfigJpaAdapter::class.java))
            .`as`("RiskConfigJpaAdapter must implement RiskConfigRepository port (ObjectProvider 주입 타입)")
            .isTrue()
        assertThat(RiskConfigJpaAdapter::class.java.getAnnotation(Component::class.java))
            .`as`("RiskConfigJpaAdapter must be @Component for consumer ComponentScan discovery")
            .isNotNull()
    }

    @Test
    fun `consumer JpaScanConfiguration basePackages가 risk JPA 어댑터 패키지를 커버한다`() {
        val adapterPackage = RiskConfigJpaAdapter::class.java.packageName // ...persistence.shared.risk

        val enableJpa = JpaScanConfiguration::class.java.getAnnotation(EnableJpaRepositories::class.java)
        val entityScan = JpaScanConfiguration::class.java.getAnnotation(EntityScan::class.java)
        assertThat(enableJpa).isNotNull()
        assertThat(entityScan).isNotNull()

        assertThat(enableJpa.basePackages.any { adapterPackage.startsWith(it) })
            .`as`("@EnableJpaRepositories must cover %s (RiskConfigJpaRepository 발견)", adapterPackage)
            .isTrue()
        assertThat(entityScan.basePackages.any { adapterPackage.startsWith(it) })
            .`as`("@EntityScan must cover %s (RiskConfigJpaEntity 발견)", adapterPackage)
            .isTrue()
    }
}
