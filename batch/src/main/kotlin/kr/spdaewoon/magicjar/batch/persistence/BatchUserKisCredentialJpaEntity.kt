package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * batch module read-only mapping for user_kis_credential.
 *
 * 시장지수/업종 수집은 사용자 주문 계좌와 무관한 market-data path 이지만,
 * 시세·캔들 정책과 동일하게 등록된 REAL KIS credential 을 사용해 KIS quotation endpoint 를 호출한다.
 */
@Embeddable
class BatchUserKisCredentialId(
    @Column(name = "user_id", length = 20, nullable = false)
    val userId: String = "",
    @Column(name = "account_type", length = 8, nullable = false)
    val accountType: String = "",
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BatchUserKisCredentialId) return false
        return userId == other.userId && accountType == other.accountType
    }

    override fun hashCode(): Int = 31 * userId.hashCode() + accountType.hashCode()
}

@Entity
@Table(name = "user_kis_credential")
class BatchUserKisCredentialJpaEntity(
    @EmbeddedId
    val id: BatchUserKisCredentialId,
    @Column(name = "account_number", length = 20, nullable = false)
    val accountNumber: String,
    @Column(name = "account_prdt_cd", length = 4, nullable = false)
    val accountPrdtCd: String = "01",
    @Column(name = "app_key_enc", nullable = false)
    val appKeyEnc: ByteArray,
    @Column(name = "secret_key_enc", nullable = false)
    val secretKeyEnc: ByteArray,
    @Column(name = "last_token_iss")
    val lastTokenIss: Instant? = null,
    @Column(name = "last_validated_at")
    val lastValidatedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        id = BatchUserKisCredentialId(),
        accountNumber = "",
        appKeyEnc = ByteArray(0),
        secretKeyEnc = ByteArray(0),
    )
}
