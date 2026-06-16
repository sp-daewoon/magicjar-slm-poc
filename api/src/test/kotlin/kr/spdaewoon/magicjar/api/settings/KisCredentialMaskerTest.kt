package kr.spdaewoon.magicjar.api.settings

import kr.spdaewoon.magicjar.api.adapter.kis.KisCredentialMasker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * KisCredentialMasker 단위 테스트 (ADR 0074 §2-2-2 + spec §2-4-1 KisCredentialMaskerTest 정합).
 *
 * 마스킹 형식 검증:
 *   - appKey: 앞 4자 + "***" + 뒤 4자
 *   - secretKey: 전부 "********"
 *   - accountNumber: 앞 4자 + "****"
 *
 * // AI 호출 0 건 (ADR 0074 §11-2 정합)
 */
class KisCredentialMaskerTest {
    @Test
    fun `maskAppKey - 정상 길이 키는 앞 4자 + *** + 뒤 4자 반환`() {
        // given
        val appKey = "PS01abcdwxyz1234"

        // when
        val masked = KisCredentialMasker.maskAppKey(appKey)

        // then
        assertThat(masked).isEqualTo("PS01***1234")
        assertThat(masked).doesNotContain("abcdwxyz")
    }

    @Test
    fun `maskAppKey - KIS PS키 전형적인 36자 키 마스킹`() {
        // given
        val appKey = "PSxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

        // when
        val masked = KisCredentialMasker.maskAppKey(appKey)

        // then
        assertThat(masked).startsWith("PSxx")
        assertThat(masked).endsWith("xxxx")
        assertThat(masked).contains("***")
        // 평문 중간 부분이 응답에 없어야 함
        assertThat(masked.length).isLessThan(appKey.length)
    }

    @Test
    fun `maskAppKey - 8자 미만 키는 *** 전체 마스킹`() {
        // given
        val shortKey = "PS01abc"

        // when
        val masked = KisCredentialMasker.maskAppKey(shortKey)

        // then
        assertThat(masked).isEqualTo("***")
    }

    @Test
    fun `maskSecretKey - 길이 무관 전부 *** 8개`() {
        // given
        val secretKey = "yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"

        // when
        val masked = KisCredentialMasker.maskSecretKey(secretKey)

        // then
        assertThat(masked).isEqualTo("********")
        assertThat(masked).doesNotContain("y")
    }

    @Test
    fun `maskAccountNumber - 앞 4자 + 4개 별표 반환`() {
        // given
        val accountNumber = "74683223"

        // when
        val masked = KisCredentialMasker.maskAccountNumber(accountNumber)

        // then
        assertThat(masked).isEqualTo("7468****")
        assertThat(masked).doesNotContain("3223")
    }

    @Test
    fun `maskAccountNumber - 4자 미만 계좌번호는 전부 별표`() {
        // given
        val shortAccount = "123"

        // when
        val masked = KisCredentialMasker.maskAccountNumber(shortAccount)

        // then
        assertThat(masked).isEqualTo("****")
    }

    @Test
    fun `maskAppKey 결과는 평문 원본보다 짧아야 함`() {
        // given: 실제 KIS APP_KEY 형식 유사 (36자)
        val appKey = "PS1234567890ABCDEFGHIJKLMNOPQRSTUVwx"

        // when
        val masked = KisCredentialMasker.maskAppKey(appKey)

        // then: 앞 4 + "***" + 뒤 4 = 11자
        assertThat(masked.length).isEqualTo(11)
        assertThat(masked.length).isLessThan(appKey.length)
    }
}
