package kr.spdaewoon.magicjar.consumer.ws

import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KIS WS AES-256-CBC 복호화 helper (hotfix 2026-05-20).
 *
 * ### 배경
 * 사용자 dogfooding evidence (2026-05-20): H0STCNI9 모의 체결통보가 `1|H0STCNI9|001|<base64>` 패턴으로
 * AES-256-CBC 암호화되어 도달. KisWsMessageParser는 평문 가정이라 처리 0 → order_request status=ACK stuck.
 *
 * ### KIS 공식 spec
 * - 알고리즘: AES-256-CBC + PKCS5Padding
 * - IV: 16 byte (UTF-8 문자열 그대로 — 구독 SUCCESS 응답 `output.iv`)
 * - Key: 32 byte (UTF-8 문자열 그대로 — 구독 SUCCESS 응답 `output.key`)
 * - Cipher text: Base64 encoded
 *
 * ### KIS-first
 * Python 공식 예제 `kis_auth.py aes_cbc_base64_dec`:
 * ```python
 * cipher = AES.new(key.encode('utf-8'), AES.MODE_CBC, iv.encode('utf-8'))
 * return bytes.decode(unpad(cipher.decrypt(b64decode(cipher_text)), AES.block_size))
 * ```
 * raw URL: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_user/kis_auth.py
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0068 §14-6).
 */
object KisWsAesDecryptor {
    private val log = LoggerFactory.getLogger(KisWsAesDecryptor::class.java)

    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"

    /**
     * KIS WS 암호화 payload 복호화 → 평문 caret-delimited body.
     *
     * @param cipherTextB64 KIS WS 메시지 4번째 필드 (base64 encoded)
     * @param iv 구독 SUCCESS 응답 `output.iv` (16 byte UTF-8)
     * @param key 구독 SUCCESS 응답 `output.key` (32 byte UTF-8)
     * @return 평문 caret(^) delimited body
     * @throws IllegalArgumentException 입력 검증 실패
     * @throws javax.crypto.BadPaddingException 복호화 실패 (key 불일치 등)
     */
    fun decrypt(
        cipherTextB64: String,
        iv: String,
        key: String,
    ): String {
        require(iv.length == 16) { "KIS WS AES IV 길이 결함: 16 byte UTF-8 의무 (실제=${iv.length})" }
        require(key.length == 32) { "KIS WS AES Key 길이 결함: 32 byte UTF-8 의무 (실제=${key.length})" }

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), KEY_ALGORITHM)
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decoded = Base64.getDecoder().decode(cipherTextB64)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }
}
