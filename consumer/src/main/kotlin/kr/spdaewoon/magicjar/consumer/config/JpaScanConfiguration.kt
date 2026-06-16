package kr.spdaewoon.magicjar.consumer.config

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * consumer 모듈 JPA scan 명시 — ADR 0088 후속 (v3.7.1 hotfix #2).
 *
 * api `JpaScanConfiguration` KDoc 참조. 동일 chain — ADR 0088 → PR #583 옵션 B
 * (`PersistenceSharedAutoConfiguration`) → 본 hotfix #2(옵션 A 단일화).
 *
 * ## basePackages 매트릭스
 *   - `kr.spdaewoon.magicjar.consumer.persistence` — consumer 자체 JpaRepository·@Entity
 *     (ConsumerOrderRequest, Position, KillSwitch 등).
 *   - `kr.spdaewoon.magicjar.persistence.shared` — Outbox·Stomp 어댑터 (ADR 0088 라이브러리 모듈).
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(
    basePackages = [
        "kr.spdaewoon.magicjar.consumer.persistence",
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
@EntityScan(
    basePackages = [
        "kr.spdaewoon.magicjar.consumer.persistence",
        "kr.spdaewoon.magicjar.persistence.shared",
    ],
)
class JpaScanConfiguration
