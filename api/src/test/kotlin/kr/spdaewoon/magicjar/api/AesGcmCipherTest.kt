package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * AesGcmCipher 단위 테스트 (spec §8-1, 룰 4 정합 — R3 일괄 아님, 단순 smoke).
 * 보안 로직이므로 round-trip 검증 포함.
 */
class AesGcmCipherTest {
    // openssl rand -base64 32 결과 (테스트 전용 — 실운영 사용 금지)
    private val testMasterKeyBase64: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private val cipher: AesGcmCipher = AesGcmCipher(testMasterKeyBase64)

    @Test
    fun `encrypt and decrypt round-trip should return original plain text`() {
        // given
        val plain = "KIS-APP-KEY-테스트-값-36자리-xxxxxxxxxxxxx".toByteArray(Charsets.UTF_8)

        // when
        val encrypted = cipher.encrypt(plain)
        val decrypted = cipher.decrypt(encrypted)

        // then
        assertThat(decrypted).isEqualTo(plain)
    }

    @Test
    fun `encrypt should prepend 12-byte IV`() {
        // given
        val plain = "test".toByteArray()

        // when
        val encrypted = cipher.encrypt(plain)

        // then: IV(12) + ciphertext + tag(16) = 최소 12 + 0 + 16 = 28 bytes
        assertThat(encrypted.size).isGreaterThan(16)
    }

    @Test
    fun `each encrypt call should produce different ciphertext (nonce uniqueness)`() {
        // given
        val plain = "same-plain-text".toByteArray()

        // when
        val enc1 = cipher.encrypt(plain)
        val enc2 = cipher.encrypt(plain)

        // then: IV가 매번 달라야 한다 (GCM nonce 재사용 금지)
        assertThat(enc1).isNotEqualTo(enc2)
    }

    @Test
    fun `blank master key should throw IllegalStateException on init`() {
        assertThatThrownBy {
            AesGcmCipher("")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("MAGICJAR_SECRET_MASTER_KEY")
    }

    @Test
    fun `wrong size master key should throw IllegalArgumentException`() {
        // 16 bytes (128-bit) — 32 bytes 필요
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy {
            AesGcmCipher(shortKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes")
    }
}
