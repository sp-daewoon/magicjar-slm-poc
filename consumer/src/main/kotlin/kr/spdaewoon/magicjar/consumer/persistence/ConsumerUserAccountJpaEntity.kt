package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * consumer 모듈의 user_account JPA 엔티티 (REAL canary hotfix — RealTradingConsentPort 배선).
 *
 * api `UserAccountJpaEntity`와 동일 테이블 매핑 — consumer 별도 Spring Boot context이므로
 * api 빈이 consumer ApplicationContext에 합류하지 못함 (ADR 0088 모듈 경계).
 * consumer-local read-only 조회 전용 — `real_trading_consent_at` 3중 게이트 조건 3 검사용.
 *
 * 매핑 범위는 동의 검사에 필요한 컬럼만 (userId PK + realTradingConsentAt).
 * 나머지 user_account 컬럼(display_name·masked_account 등)은 consumer가 쓰지 않으므로 매핑 생략.
 */
@Entity
@Table(name = "user_account")
class ConsumerUserAccountJpaEntity(
    @Id
    @Column(name = "user_id", length = 20, nullable = false, updatable = false)
    val userId: String,
    /**
     * T-O 실계좌 주문 동의 토글 — NULL이면 모의만 가능 (3중 게이트 조건 3, ADR 0068 §14-3 + V23).
     */
    @Column(name = "real_trading_consent_at")
    val realTradingConsentAt: Instant? = null,
) {
    // no-arg constructor for JPA
    protected constructor() : this(userId = "")
}
