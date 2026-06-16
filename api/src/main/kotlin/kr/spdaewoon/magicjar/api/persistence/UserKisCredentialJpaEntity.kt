package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * user_kis_credential 복합 PK (user_id, account_type).
 */
@Embeddable
class UserKisCredentialId(
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String = "",
    @Column(name = "account_type", length = 8, nullable = false)
    val accountType: String = "",
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserKisCredentialId) return false
        return userId == other.userId && accountType == other.accountType
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + accountType.hashCode()
}

/**
 * user_kis_credential JPA 엔티티 (ADR 0046 §2-1 C).
 * AES-256-GCM 암호화 후 BYTEA 저장.
 */
@Entity
@Table(name = "user_kis_credential")
class UserKisCredentialJpaEntity(
    @EmbeddedId
    val id: UserKisCredentialId,
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
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    // no-arg constructor for JPA
    protected constructor() : this(
        id = UserKisCredentialId(),
        accountNumber = "",
        appKeyEnc = ByteArray(0),
        secretKeyEnc = ByteArray(0),
    )
}
