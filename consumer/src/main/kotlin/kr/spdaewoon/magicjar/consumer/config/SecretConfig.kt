package kr.spdaewoon.magicjar.consumer.config

import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Consumer AES-256-GCM SecretCipher Bean 설정 (ADR 0046 §2-4).
 * api 모듈의 AesGcmCipher와 동일 구현 — consumer가 KIS 키 복호화에 사용.
 * MAGICJAR_SECRET_MASTER_KEY 동일 환경변수 공유 (docker 배포 시 동일 .env).
 */
@Configuration
class SecretConfig {
    private val log = LoggerFactory.getLogger(SecretConfig::class.java)

    @Bean
    fun secretCipher(
        @Value("\${magicjar.secret.master-key:}") masterKeyBase64: String,
    ): SecretCipher {
        if (masterKeyBase64.isBlank()) {
            log.error("MAGICJAR_SECRET_MASTER_KEY 부재 — .env에 base64(32 bytes) 등록 후 재시작")
            error("MAGICJAR_SECRET_MASTER_KEY 부재 — 부팅 거부")
        }
        val keyBytes =
            runCatching { Base64.getDecoder().decode(masterKeyBase64) }
                .getOrElse { ex ->
                    log.error("MAGICJAR_SECRET_MASTER_KEY base64 디코딩 실패: ${ex.message}")
                    error("MAGICJAR_SECRET_MASTER_KEY base64 디코딩 실패 — 부팅 거부")
                }
        require(keyBytes.size == 32) {
            "마스터 키는 32 bytes (base64 44자) — 현재 ${keyBytes.size} bytes"
        }
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val secureRandom = SecureRandom()

        return object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                val iv = ByteArray(12)
                secureRandom.nextBytes(iv)
                val cipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    }
                return iv + cipher.doFinal(plain)
            }

            override fun decrypt(cipher: ByteArray): ByteArray {
                require(cipher.size > 12) { "암호문 길이 부족" }
                val iv = cipher.copyOfRange(0, 12)
                val ciphertextWithTag = cipher.copyOfRange(12, cipher.size)
                val aesCipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    }
                return aesCipher.doFinal(ciphertextWithTag)
            }
        }
    }
}
