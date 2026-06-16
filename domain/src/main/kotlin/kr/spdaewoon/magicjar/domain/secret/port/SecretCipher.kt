package kr.spdaewoon.magicjar.domain.secret.port

/**
 * AES-256-GCM 암호화 포트 (ADR 0046 §2-4).
 * 구현은 api 모듈 config 어댑터에서 (master key = 환경변수 MAGICJAR_SECRET_MASTER_KEY).
 * PR-5에서는 포트 정의만 — 실 구현은 PR-6 T-ONBOARD.
 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(cipher: ByteArray): ByteArray
}
