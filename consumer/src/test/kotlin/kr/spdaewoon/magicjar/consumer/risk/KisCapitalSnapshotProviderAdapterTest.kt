package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceRateLimitedException
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * KisCapitalSnapshotProviderAdapter 단위 테스트 — 주문경로 자본 산출 (ADR 0116 R1-3 + **F10 hotfix**).
 *
 * ## F10 (2026-06-12 최고 관리자 결재) — 0 fabricate 폐기 → 재시도(하부)·stale-cache·skip
 * 14:12 incident: rate limit 실패 시 capital=0 fabricate → POSITION_LIMIT 거짓 차단. 본 테스트가 닫는 것:
 * 1. KIS fresh 조회 성공 → Available(값, stale=false) + snapshot 캐시 적재
 * 2. 조회 실패(rate limit) + 유효 TTL 내 직전 성공값 존재 → Available(직전값, stale=true) — **0 아님**
 * 3. 조회 실패 + stale-cache 없음 → Unavailable(skip) — **0 fabricate 부재**
 * 4. 조회 실패 + stale-cache TTL 초과 → Unavailable(skip)
 * 5. provider bean 부재 → Unavailable(skip)
 * 6. KIS fresh 0 netAssetValue(실제 0 잔고) → Available(0) — 진짜 0 은 정상값
 * 7. 직전 성공 90초 내 재호출 → KIS 직접 조회 없이 fresh-cache 재사용(EGW00215 자가압박 완화)
 * 8. 직접 조회 실패 직후 cooldown → 캐시가 없어도 매분 KIS 재시도 폭주 없이 Unavailable(skip)
 *
 * mock 합성 0 LOCK (feedback_no_mock_data): 본 테스트는 fixture (테스트 fixture 면제).
 * AI 호출 0 LOCK (룰 5 + ADR 0023 §2-4): LLM provider import 0.
 */
class KisCapitalSnapshotProviderAdapterTest {
    private val balanceQueryPort = mockk<KisBalanceQueryPort>()

    @Suppress("UNCHECKED_CAST")
    private val portProvider = mockk<ObjectProvider<KisBalanceQueryPort>>()

    private val userId = "user_test_001"
    private var nowRef: Instant = Instant.parse("2026-05-18T05:30:00Z")

    // magicjarMode default MOCK → accountType=MOCK. clock 주입으로 TTL 경과 시뮬.
    private val sut =
        KisCapitalSnapshotProviderAdapter(
            balanceQueryPortProvider = portProvider,
            magicjarMode = "MOCK",
            staleCacheTtlMinutes = 5,
            clock = { nowRef },
        )

    private fun fixtureSnapshot(netAssetValue: BigDecimal = BigDecimal("12000000.00")): KisAccountBalance =
        KisAccountBalance(
            userId = userId,
            accountType = AccountType.MOCK,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000.00"),
                    cashD1 = BigDecimal("10000000.00"),
                    cashD2 = BigDecimal("10000000.00"),
                    totalEvaluation = BigDecimal("12000000.00"),
                    netAssetValue = netAssetValue,
                    totalPurchaseAmount = BigDecimal("1900000.00"),
                    totalHoldingEvaluation = BigDecimal("2000000.00"),
                    totalProfitLoss = BigDecimal("100000.00"),
                    assetIncreaseDecrease = BigDecimal("50000.00"),
                    assetIncreaseDecreaseRate = BigDecimal("0.42"),
                    fetchedAt = nowRef,
                ),
            holdings =
                listOf(
                    AccountHolding(
                        code = "005930",
                        name = "삼성전자",
                        quantity = 10,
                        avgPrice = BigDecimal("70000.00"),
                        currentPrice = BigDecimal("71000.00"),
                        evaluationAmount = BigDecimal("710000.00"),
                        profitLoss = BigDecimal("10000.00"),
                        profitLossRate = BigDecimal("1.43"),
                    ),
                ),
            fetchedAt = nowRef,
        )

    @Test
    fun `KIS fresh 조회 성공 — Available 값 반환 (캐시 우회) + snapshot 적재`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal("12000000.00"))

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Available::class.java)
        val available = outcome as CapitalOutcome.Available
        assertThat(available.capital).isEqualByComparingTo("12000000.00")
        assertThat(available.stale).isFalse()
        verify(exactly = 1) { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) }
    }

    @Test
    fun `직전 성공 90초 내 재호출은 KIS 직접 조회 없이 fresh-cache 를 재사용한다`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal("12000000.00"))

        sut.capitalOutcome(userId)

        nowRef = nowRef.plusSeconds(60)
        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Available::class.java)
        val available = outcome as CapitalOutcome.Available
        assertThat(available.capital).isEqualByComparingTo("12000000.00")
        assertThat(available.stale).isTrue()
        verify(exactly = 1) { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) }
    }

    @Test
    fun `F10 — rate limit 재시도 소진 + 유효 TTL 내 직전 성공값 존재 → stale-cache 사용 (0 fabricate 아님)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort

        // 1) 첫 호출 성공 → snapshot 캐시 적재
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal("12000000.00"))
        sut.capitalOutcome(userId)

        // 2) 3분 후 rate limit 재시도 소진 → 직전 성공값(stale) 재사용
        nowRef = nowRef.plusSeconds(180)
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } throws
            KisBalanceRateLimitedException(userId, 2, RuntimeException("EGW00201"))

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Available::class.java)
        val available = outcome as CapitalOutcome.Available
        assertThat(available.capital).isEqualByComparingTo("12000000.00")
        assertThat(available.stale).isTrue()
    }

    @Test
    fun `F10 — 조회 실패 + stale-cache 없음 → Unavailable skip (0 fabricate 부재)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } throws
            KisBalanceRateLimitedException(userId, 2, RuntimeException("EGW00201"))

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Unavailable::class.java)
        assertThat((outcome as CapitalOutcome.Unavailable).reason).isEqualTo("KIS_RATE_LIMIT")
    }

    @Test
    fun `직접 조회 실패 직후 cooldown 중 재호출은 KIS 직접 조회 없이 skip한다`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } throws RuntimeException("upstream 5xx")

        val first = sut.capitalOutcome(userId)
        nowRef = nowRef.plusSeconds(60)
        val second = sut.capitalOutcome(userId)

        assertThat(first).isInstanceOf(CapitalOutcome.Unavailable::class.java)
        assertThat(second).isInstanceOf(CapitalOutcome.Unavailable::class.java)
        assertThat((second as CapitalOutcome.Unavailable).reason).contains("KIS_LOOKUP_COOLDOWN")
        verify(exactly = 1) { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) }
    }

    @Test
    fun `F10 — 조회 실패 + stale-cache TTL 초과 → Unavailable skip`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort

        // 첫 성공 → 캐시 적재
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal("12000000.00"))
        sut.capitalOutcome(userId)

        // TTL(5분) 초과한 6분 뒤 실패 → 직전값 만료 → skip
        nowRef = nowRef.plusSeconds(360)
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } throws
            RuntimeException("upstream 5xx")

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Unavailable::class.java)
    }

    @Test
    fun `F10 — provider bean 부재 → Unavailable skip (0 fabricate 부재)`() {
        every { portProvider.getIfAvailable() } returns null

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Unavailable::class.java)
        assertThat((outcome as CapitalOutcome.Unavailable).reason).isEqualTo("PROVIDER_UNAVAILABLE")
    }

    @Test
    fun `KIS fresh 0 netAssetValue — Available 0 (실제 0 잔고는 정상값)`() {
        every { portProvider.getIfAvailable() } returns balanceQueryPort
        every { balanceQueryPort.fetchFullBalance(userId, AccountType.MOCK) } returns
            fixtureSnapshot(BigDecimal.ZERO)

        val outcome = sut.capitalOutcome(userId)

        assertThat(outcome).isInstanceOf(CapitalOutcome.Available::class.java)
        assertThat((outcome as CapitalOutcome.Available).capital).isEqualByComparingTo("0")
    }

    @Test
    fun `레거시 getCapital — Available 은 값, Unavailable 은 0 환원 (하위호환)`() {
        every { portProvider.getIfAvailable() } returns null
        assertThat(sut.getCapital(userId)).isEqualByComparingTo("0")
    }
}
