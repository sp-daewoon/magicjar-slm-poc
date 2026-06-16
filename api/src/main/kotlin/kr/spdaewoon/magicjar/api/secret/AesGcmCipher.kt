package kr.spdaewoon.magicjar.api.secret

import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 암호화 구현 (ADR 0046 §2-4).
 *
 * 저장 포맷: [ IV (12 bytes) ][ ciphertext (가변) ][ tag (16 bytes) ]
 * - IV는 매 encrypt마다 SecureRandom.nextBytes(12) (재사용 절대 금지 — GCM nonce 재사용 = 키 노출)
 * - Java GCM API는 ciphertext + tag를 합친 byte array 반환 → IV만 prepend
 * - BYTEA 컬럼 저장: IV(12) + JCipher.doFinal(plain)
 *
 * 마스터 키: MAGICJAR_SECRET_MASTER_KEY (base64 32 bytes = 256 bits)
 * 생성: openssl rand -base64 32
 *
 * 부팅 시 검증: init 블록에서 require() 위반 시 ApplicationContextException 발생 → EXITED 1
 */
@Component
class AesGcmCipher(
    @Value("\${magicjar.secret.master-key:}") private val masterKeyBase64: String,
) : SecretCipher {
    private val log = LoggerFactory.getLogger(AesGcmCipher::class.java)

    private val secretKey: SecretKeySpec
    private val secureRandom: SecureRandom = SecureRandom()

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val REQUIRED_KEY_SIZE_BYTES = 32
    }

    init {
        if (masterKeyBase64.isBlank()) {
            log.error(
                "MAGICJAR_SECRET_MASTER_KEY 부재 — .env에 base64(32 bytes) 등록 후 재시작. " +
                    "생성: openssl rand -base64 32",
            )
            error("MAGICJAR_SECRET_MASTER_KEY 부재 — 부팅 거부")
        }

        val keyBytes =
            runCatching { Base64.getDecoder().decode(masterKeyBase64) }
                .getOrElse { ex ->
                    log.error("MAGICJAR_SECRET_MASTER_KEY base64 디코딩 실패: ${ex.message}")
                    error("MAGICJAR_SECRET_MASTER_KEY base64 디코딩 실패 — 부팅 거부")
                }

        require(keyBytes.size == REQUIRED_KEY_SIZE_BYTES) {
            "마스터 키는 32 bytes (base64 44자) — 현재 ${keyBytes.size} bytes. 생성: openssl rand -base64 32"
        }

        secretKey = SecretKeySpec(keyBytes, KEY_ALGORITHM)
        log.info("AesGcmCipher 초기화 완료 — AES-256-GCM 부팅 검증 OK")
    }

    /**
     * 평문 ByteArray를 AES-256-GCM으로 암호화.
     * 반환값: IV(12 bytes) + ciphertext + tag(16 bytes)
     */
    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)

        val cipher =
            Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE_BITS, iv))
            }
        val ciphertextWithTag = cipher.doFinal(plain)

        // IV(12) + ciphertext + tag(16) 순으로 concat
        return iv + ciphertextWithTag
    }

    /**
     * encrypt() 반환값(IV + ciphertext + tag)을 복호화.
     */
    override fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size > IV_SIZE_BYTES) {
            "암호문 길이 부족 — 최소 ${IV_SIZE_BYTES + 1} bytes 필요, 실제: ${cipher.size}"
        }

        val iv = cipher.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertextWithTag = cipher.copyOfRange(IV_SIZE_BYTES, cipher.size)

        val aesCipher =
            Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_SIZE_BITS, iv))
            }
        return aesCipher.doFinal(ciphertextWithTag)
    }
}
