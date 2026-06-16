package kr.spdaewoon.magicjar.consumer.order

import kr.spdaewoon.magicjar.consumer.config.JpaScanConfiguration
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerRealTradingConsentJpaAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserAccountJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserAccountJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.RealTradingConsentPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.support.registerBean
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.stereotype.Component

/**
 * RealTradingConsentPort consumer 빈 합류 회귀 차단 테스트 (헌법 §11-1 — 이번 결함의 재발 방어 핵심).
 *
 * ## 결함 재발 원인 (라이브 evidence — signalId=717 REAL_GATE_DENIED:CONSENT_PORT_UNAVAILABLE)
 * RealTradingConsentPort 구현 빈이 consumer ApplicationContext에 0개일 때 silent 부팅 →
 * TradingModeGateImpl이 모든 REAL intent 거부. 컴파일 GREEN으로는 잡히지 않는 배선 결함.
 *
 * ## 재발 트리거 두 가지를 정적으로 차단
 *   1. [ConsumerRealTradingConsentJpaAdapter] @Component 누락 → component scan 미합류
 *   2. JpaScanConfiguration basePackages 누락 → @Entity/JpaRepository 미합류 (어댑터 생성자 의존 불충족)
 *
 * 추가로 @Component 빈이 실제 ApplicationContext에서 RealTradingConsentPort 타입으로
 * 1개 합류함을 가벼운 컨텍스트로 증명 (JPA·DB 인프라 없이 어댑터 + 가짜 repository 빈).
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class ConsumerConsentPortWiringTest {
    @Test
    fun `어댑터는 @Component 로 표시되어 consumer component scan 에 합류한다`() {
        assertThat(ConsumerRealTradingConsentJpaAdapter::class.java.isAnnotationPresent(Component::class.java))
            .`as`("ConsumerRealTradingConsentJpaAdapter 에 @Component 가 있어야 빈으로 합류 (회귀 차단)")
            .isTrue()
    }

    @Test
    fun `어댑터와 엔티티는 JpaScanConfiguration 이 scan 하는 패키지에 위치한다`() {
        val enableJpa = JpaScanConfiguration::class.java.getAnnotation(EnableJpaRepositories::class.java)
        val entityScan = JpaScanConfiguration::class.java.getAnnotation(EntityScan::class.java)

        val adapterPackage = ConsumerRealTradingConsentJpaAdapter::class.java.packageName
        val repoPackage = ConsumerUserAccountJpaRepository::class.java.packageName
        val entityPackage = ConsumerUserAccountJpaEntity::class.java.packageName

        // @EnableJpaRepositories 가 repository 패키지를 포함해야 JpaRepository 빈 생성
        assertThat(enableJpa.basePackages.any { repoPackage.startsWith(it) })
            .`as`("JpaScanConfiguration @EnableJpaRepositories 가 $repoPackage 를 scan 해야 함")
            .isTrue()
        // @EntityScan 이 엔티티 패키지를 포함해야 @Entity 매핑
        assertThat(entityScan.basePackages.any { entityPackage.startsWith(it) })
            .`as`("JpaScanConfiguration @EntityScan 이 $entityPackage 를 scan 해야 함")
            .isTrue()
        // 어댑터(@Component)도 동일 패키지 트리에 위치 — scanBasePackages=kr.spdaewoon.magicjar 하위
        assertThat(adapterPackage).startsWith("kr.spdaewoon.magicjar")
    }

    @Test
    fun `RealTradingConsentPort 타입 빈이 ApplicationContext 에 정확히 1개 합류한다`() {
        // JPA·DB 없이 어댑터 + 가짜 repository 빈만으로 합류·주입을 증명.
        val ctx = AnnotationConfigApplicationContext()
        ctx.registerBean<ConsumerUserAccountJpaRepository> { fakeRepository() }
        ctx.register(ConsumerRealTradingConsentJpaAdapter::class.java)
        ctx.refresh()
        try {
            val beans = ctx.getBeansOfType(RealTradingConsentPort::class.java)
            assertThat(beans)
                .`as`("RealTradingConsentPort 구현 빈이 consumer context 에 정확히 1개 (CONSENT_PORT_UNAVAILABLE 회귀 차단)")
                .hasSize(1)
            assertThat(beans.values.single()).isInstanceOf(ConsumerRealTradingConsentJpaAdapter::class.java)
        } finally {
            ctx.close()
        }
    }

    /** JPA 인프라 없이 합류만 증명하기 위한 no-op repository (조회 메서드 미호출 — Object 메서드만 처리). */
    private fun fakeRepository(): ConsumerUserAccountJpaRepository =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ConsumerUserAccountJpaRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fakeConsumerUserAccountJpaRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> error("repository ${method.name} not invoked in wiring test")
            }
        } as ConsumerUserAccountJpaRepository
}
