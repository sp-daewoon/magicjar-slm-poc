package kr.spdaewoon.magicjar.api.adapter.kis

/**
 * KIS 자격증명 마스킹 헬퍼 (ADR 0074 §2-2-2 정합 + 메모리 feedback_secret_handling.md).
 *
 * 마스킹 규칙:
 *   - appKey:   앞 4자 + "***" + 뒤 4자 (예: "PS01***wxyz")
 *   - secretKey: 전부 "********" (비교 불가)
 *   - accountNumber: DB GENERATED masked_account 컬럼 직접 사용 (앞 4자 + "****")
 *
 * 평문 키를 절대 로그·응답 body에 포함하지 않음 (ADR 0046 §2-5).
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
object KisCredentialMasker {
    /**
     * appKey 마스킹 — 앞 4자 + "***" + 뒤 4자.
     * 길이가 8자 미만이면 전부 "***"로 대체.
     */
    fun maskAppKey(plain: String): String {
        if (plain.length < 8) return "***"
        return plain.take(4) + "***" + plain.takeLast(4)
    }

    /**
     * secretKey 마스킹 — 전부 "********".
     * 길이 무관.
     */
    fun maskSecretKey(
        @Suppress("UNUSED_PARAMETER") plain: String,
    ): String = "********"

    /**
     * accountNumber 마스킹 — 앞 4자 + "****".
     * DB의 masked_account GENERATED 컬럼 형식과 동일.
     */
    fun maskAccountNumber(accountNumber: String): String {
        if (accountNumber.length < 4) return "****"
        return accountNumber.take(4) + "****"
    }
}
