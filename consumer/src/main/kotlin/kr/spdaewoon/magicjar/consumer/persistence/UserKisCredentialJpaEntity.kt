package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * consumer 모듈의 user_kis_credential JPA 엔티티.
 * api 모듈과 동일 테이블 매핑 — consumer 별도 Spring Boot context.
 * Bootstrap 시 KIS 키 조회 전용 (read-only 사용).
 */
@Embeddable
class ConsumerUserKisCredentialId(
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String = "",
    @Column(name = "account_type", length = 8, nullable = false)
    val accountType: String = "",
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConsumerUserKisCredentialId) return false
        return userId == other.userId && accountType == other.accountType
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + accountType.hashCode()
}

@Entity
@Table(name = "user_kis_credential")
class ConsumerUserKisCredentialJpaEntity(
    @EmbeddedId
    val id: ConsumerUserKisCredentialId,
    @Column(name = "account_number", length = 20, nullable = false)
    val accountNumber: String,
    @Column(name = "account_prdt_cd", length = 4, nullable = false)
    val accountPrdtCd: String = "01",
    @Column(name = "app_key_enc", nullable = false)
    val appKeyEnc: ByteArray,
    @Column(name = "secret_key_enc", nullable = false)
    val secretKeyEnc: ByteArray,
    @Column(name = "last_token_iss")
    var lastTokenIss: Instant? = null,
    @Column(name = "last_validated_at")
    var lastValidatedAt: Instant? = null,
) {
    protected constructor() : this(
        id = ConsumerUserKisCredentialId(),
        accountNumber = "",
        appKeyEnc = ByteArray(0),
        secretKeyEnc = ByteArray(0),
    )
}
