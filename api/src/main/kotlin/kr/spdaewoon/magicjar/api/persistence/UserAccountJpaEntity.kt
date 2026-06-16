package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * user_account JPA 엔티티 (ADR 0046 §2-1 B).
 * userId = KIS 실전 계좌번호 8자리 (자연 키).
 * masked_account 는 GENERATED ALWAYS AS 컬럼 (V2 마이그레이션) — insertable=false, updatable=false.
 */
@Entity
@Table(name = "user_account")
class UserAccountJpaEntity(
    @Id
    @Column(name = "user_id", length = 20, nullable = false, updatable = false)
    val userId: String,
    @Column(name = "display_name", length = 100)
    var displayName: String? = null,
    @Column(name = "masked_account", length = 20, insertable = false, updatable = false)
    var maskedAccount: String = "",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    /**
     * T-O 실계좌 주문 동의 토글 — NULL이면 모의만 가능 (3중 게이트 3번째 조건, ADR 0068 §14-3 + V23).
     */
    @Column(name = "real_trading_consent_at")
    var realTradingConsentAt: Instant? = null,
) {
    // no-arg constructor for JPA
    protected constructor() : this(userId = "")
}
