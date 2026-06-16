package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ExternalOrderAccountResolver 단위 테스트 — 2026-06-05 외부 주문 흡수 user 매핑·mode fix.
 *
 * 매트릭스:
 *  (a) 계좌번호 매칭 → 내부 userId + accountType→mode 환산 (Mapped)
 *  (b) MOCK 계좌 → mode=MOCK (canary 오염 방지 핵심)
 *  (c) 매칭 없음 → Unmapped (FK 폭발 회피 — caller 가 보류)
 *  (d) 공백 계좌번호 → Unmapped
 *  (e) CANO-PRDT 포맷 정규화 (앞 8자리)
 *  (f) 다중 매칭 시 runtime mode 우선
 */
class ExternalOrderAccountResolverTest {
    private val credentialRepo = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)

    private fun newResolver(magicjarMode: String = "MOCK") =
        ExternalOrderAccountResolver(credentialRepository = credentialRepo, magicjarMode = magicjarMode)

    private fun cred(
        userId: String,
        accountType: String,
        accountNumber: String,
    ) = ConsumerUserKisCredentialJpaEntity(
        id = ConsumerUserKisCredentialId(userId = userId, accountType = accountType),
        accountNumber = accountNumber,
        appKeyEnc = ByteArray(0),
        secretKeyEnc = ByteArray(0),
    )

    @Test
    fun `(a) 계좌번호 매칭 — Mapped 내부 userId 반환`() {
        every { credentialRepo.findAllByAccountNumber("74683223") } returns
            listOf(cred(userId = "74683223", accountType = "REAL", accountNumber = "74683223"))

        val result = newResolver(magicjarMode = "REAL").resolve(accountNo = "74683223", htsId = "thdeodns")

        assertThat(result).isInstanceOf(ExternalOrderAccountResolver.Resolution.Mapped::class.java)
        val mapped = result as ExternalOrderAccountResolver.Resolution.Mapped
        assertThat(mapped.userId).isEqualTo("74683223")
        assertThat(mapped.mode).isEqualTo(TradingMode.REAL)
        assertThat(mapped.accountType).isEqualTo(AccountType.REAL)
    }

    @Test
    fun `(b) MOCK 계좌 매칭 — mode=MOCK (canary cap 오염 방지)`() {
        every { credentialRepo.findAllByAccountNumber("74683223") } returns
            listOf(cred(userId = "74683223", accountType = "MOCK", accountNumber = "74683223"))

        val result = newResolver(magicjarMode = "MOCK").resolve(accountNo = "74683223", htsId = "thdeodns")

        val mapped = result as ExternalOrderAccountResolver.Resolution.Mapped
        assertThat(mapped.mode).isEqualTo(TradingMode.MOCK)
    }

    @Test
    fun `(c) 매칭 없음 — Unmapped (FK 폭발 회피)`() {
        every { credentialRepo.findAllByAccountNumber("99999999") } returns emptyList()

        val result = newResolver().resolve(accountNo = "99999999", htsId = "unknown")

        assertThat(result).isInstanceOf(ExternalOrderAccountResolver.Resolution.Unmapped::class.java)
        assertThat((result as ExternalOrderAccountResolver.Resolution.Unmapped).reason).isEqualTo("ACCOUNT_NOT_REGISTERED")
    }

    @Test
    fun `(d) 공백 계좌번호 — Unmapped`() {
        val result = newResolver().resolve(accountNo = "   ", htsId = "x")

        assertThat(result).isInstanceOf(ExternalOrderAccountResolver.Resolution.Unmapped::class.java)
        assertThat((result as ExternalOrderAccountResolver.Resolution.Unmapped).reason).isEqualTo("BLANK_ACCOUNT_NO")
    }

    @Test
    fun `(e) CANO-PRDT 포맷 정규화 — 앞 8자리 CANO 로 조회`() {
        val resolver = newResolver()
        assertThat(resolver.normalizeCano("74683223-01")).isEqualTo("74683223")
        assertThat(resolver.normalizeCano("746832230001")).isEqualTo("74683223")
        assertThat(resolver.normalizeCano("  74683223  ")).isEqualTo("74683223")
    }

    @Test
    fun `(f) 다중 매칭 — runtime mode 우선 선택`() {
        every { credentialRepo.findAllByAccountNumber("74683223") } returns
            listOf(
                cred(userId = "74683223", accountType = "MOCK", accountNumber = "74683223"),
                cred(userId = "74683223", accountType = "REAL", accountNumber = "74683223"),
            )

        val result = newResolver(magicjarMode = "REAL").resolve(accountNo = "74683223", htsId = "thdeodns")

        val mapped = result as ExternalOrderAccountResolver.Resolution.Mapped
        assertThat(mapped.mode).isEqualTo(TradingMode.REAL)
    }
}
