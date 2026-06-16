package kr.spdaewoon.magicjar.api.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * api 모듈 JPA scan 명시 — ADR 0088 후속 (v3.7.1 hotfix #2).
 *
 * ## 발행 이유 (chain: ADR 0088 → PR #583 옵션 B → 본 hotfix #2)
 *
 * 1) ADR 0088 — `persistence-shared` 라이브러리 모듈 분리. Outbox/Stomp 어댑터를 api·consumer 공유.
 * 2) PR #583 옵션 B — `PersistenceSharedAutoConfiguration` `@AutoConfiguration` 발행. `persistence-shared`
 *    JpaRepository 발견을 위해 `@EnableJpaRepositories(basePackages = "kr.spdaewoon.magicjar.persistence.shared")`
 *    명시.
 * 3) v3.7.0 cut 직후 빈 DB 부팅 dogfooding 시 새 결함 노출:
 *    ```
 *    APPLICATION FAILED TO START
 *    Parameter 0 of constructor in OnboardingGuardFilter required a bean of type
 *    'kr.spdaewoon.magicjar.api.persistence.UserAccountJpaRepository' that could not be found.
 *    ```
 *
 * Root cause:
 *   Spring Boot `JpaRepositoriesAutoConfiguration`은 `@ConditionalOnMissingBean(RepositoryFactoryBeanSupport::class)`
 *   으로 동작. PR #583의 `@EnableJpaRepositories`가 한 번이라도 등장하면 default JPA scan(main class
 *   패키지 fallback)이 비활성화되어 `kr.spdaewoon.magicjar.api.persistence.*` JpaRepository 발견 불가.
 *
 * ## Fix 방식 — 옵션 A 단일화 + 별도 `@Configuration` 분리
 *
 * PR #583 옵션 B(`PersistenceSharedAutoConfiguration`)는 제거. 본 `JpaScanConfiguration`이 api 자체 패키지
 * + persistence-shared 패키지 모두 명시 scan하여 단일 책임 보장.
 *
 * Application class 본체에 `@EnableJpaRepositories` 직접 명시 시 `@WebMvcTest`가 Application class를
 * 로드할 때 JPA stack(`entityManagerFactory`)이 강제 활성화되어 80건 컨트롤러 슬라이스 회귀.
 *
 * 별도 `@Configuration` class로 분리하면:
 *   - 부팅 path: `@ComponentScan(scanBasePackages = "kr.spdaewoon.magicjar")`에 의해 발견 → JPA scan 적용.
 *   - `@WebMvcTest` slice: `TypeExcludeFilters`가 일반 `@Configuration`을 제외하므로 JPA 활성화 안 됨.
 *
 * ## basePackages 매트릭스
 *   - `kr.spdaewoon.magicjar.api.persistence` — api 자체 JpaRepository·@Entity (UserAccount, Instrument, Candle 등).
 *   - `kr.spdaewoon.magicjar.persistence.shared` — Outbox·Stomp 어댑터 (ADR 0088 라이브러리 모듈).
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
@EntityScan(
    basePackages = [
        "kr.spdaewoon.magicjar.api.persistence",
        "kr.spdaewoon.magicjar.api.mcp.persistence",
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
class JpaScanConfiguration
/*
 * 2026-05-24 W4 R3 qa hotfix — McpAuditJpaRepository scan path 추가.
 *
 * R2 backend-core T-MCP Phase 1 (PR #679)에서 McpAuditJpaRepository를 신규
 * `kr.spdaewoon.magicjar.api.mcp.persistence` 패키지에 추가했으나 본 화이트리스트에 등록 누락.
 *
 * 증상: `magicjar-api` 부팅 abort
 *   APPLICATION FAILED TO START — McpAuditService 의존성 McpAuditJpaRepository bean 미발견.
 *
 * 본 hotfix: basePackages + entityScan 둘 다 `api.mcp.persistence` 추가하여 단일 책임 보장.
 *
 * 재발 방지 — 신규 패키지에 JpaRepository/Entity 추가 시 본 화이트리스트 갱신 의무.
 * (장기 fix는 sub-package 자동 스캔으로 ADR amend 후보 — backend-core 영역 dispatch.)
 */
