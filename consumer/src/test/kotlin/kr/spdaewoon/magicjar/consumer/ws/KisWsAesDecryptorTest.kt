package kr.spdaewoon.magicjar.consumer.ws

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KisWsAesDecryptor 단위 테스트 (hotfix 2026-05-20).
 *
 * KIS WS 공식 spec AES-256-CBC + PKCS5Padding round-trip 검증.
 * iv 16 byte UTF-8 + key 32 byte UTF-8 (raw bytes — KIS Python `key.encode('utf-8')` 정합).
 */
class KisWsAesDecryptorTest {
    private val testIv = "42e0241e013374b7" // 16 byte UTF-8 — KIS evidence 정확 케이스
    private val testKey = "ygnhwyxihoelqgncngyuyruvutxfb123" // 32 byte UTF-8 (KIS는 32자 ascii key)

    @Test
    fun `AES round-trip — encrypt → decrypt = 원본 평문`() {
        val plainText = "005930^090012^60900^100^20260507" // KIS H0STCNI9 caret-delimited body 예시
        val encrypted = encryptForTest(plainText, testIv, testKey)

        val decrypted = KisWsAesDecryptor.decrypt(encrypted, testIv, testKey)

        assertThat(decrypted).isEqualTo(plainText)
    }

    @Test
    fun `iv 길이 결함 — 16 byte 외 IllegalArgumentException`() {
        assertThatThrownBy {
            KisWsAesDecryptor.decrypt("AAAA", "short", testKey)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("IV 길이 결함")
    }

    @Test
    fun `key 길이 결함 — 32 byte 외 IllegalArgumentException`() {
        assertThatThrownBy {
            KisWsAesDecryptor.decrypt("AAAA", testIv, "short")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Key 길이 결함")
    }

    /** test helper — KIS Python aes_cbc_base64_enc 1:1 패턴. */
    private fun encryptForTest(
        plainText: String,
        iv: String,
        key: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }
}
