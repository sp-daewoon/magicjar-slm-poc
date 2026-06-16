package kr.spdaewoon.magicjar.consumer.bootstrap

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.account.AccountSnapshotCoordinator
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisPositionCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisBalanceAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.PositionJpaRepository
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.position.port.KisBalance
import kr.spdaewoon.magicjar.domain.position.port.KisHolding
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Instant

/**
 * PositionBootstrapRunner 단위 테스트 (ADR 0042 idempotency·실패 fallback — 룰 4 정합).
 * - 테스트 1: 활성 사용자 0명 → idle return (KIS 호출 0건)
 * - 테스트 2: KIS 오류 → KillSwitch auto-ON + 다른 사용자 계속
 * - A 의제 hotfix (2026-05-20) — 3중 게이트 검증 신규:
 *   - 테스트 3: magicjar.mode=MOCK → Bootstrap skip (KIS 호출 0)
 *   - 테스트 4: magicjar.allow-real=false → Bootstrap skip
 *   - 테스트 5: magicjar.bootstrap.position-on-startup=false → Bootstrap skip (명시 비활성)
 * - B 의제 hotfix (2026-05-20) — retry 메커니즘 신규:
 *   - 테스트 6: KIS 500 1회 실패 + 2회째 성공 → Bootstrap 성공 (KillSwitch 0)
 *   - 테스트 7: KIS 500 maxAttempts 도달 → 최종 fail + KillSwitch ON (기존 동작 유지)
 *   - 테스트 8: KIS 401 (non-retryable) → retry 0 + 즉시 fail + KillSwitch ON
 */
class PositionBootstrapRunnerTest {
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()
    private val kisBalanceAdapter = mockk<KisBalanceAdapter>()
    private val positionJpaRepository = mockk<PositionJpaRepository>()
    private val instrumentJpaRepository = mockk<ConsumerInstrumentJpaRepository>()
    private val redisPositionCacheAdapter = mockk<RedisPositionCacheAdapter>()
    private val killSwitchJpaRepository = mockk<KillSwitchJpaRepository>()
    private val auditTrailJpaRepository = mockk<ConsumerAuditTrailJpaRepository>()

    /** 3중 게이트 충족 default runner (REAL + allowReal + bootstrapOnStartup=true) */
    private val runner = newRunner(mode = "REAL", allowReal = true, bootstrapOnStartup = true)

    private fun newRunner(
        mode: String,
        allowReal: Boolean,
        bootstrapOnStartup: Boolean,
        accountSnapshotCoordinatorProvider: ObjectProvider<AccountSnapshotCoordinator>? = null,
    ): PositionBootstrapRunner =
        PositionBootstrapRunner(
            credentialRepository = credentialRepository,
            kisBalanceAdapter = kisBalanceAdapter,
            positionJpaRepository = positionJpaRepository,
            instrumentJpaRepository = instrumentJpaRepository,
            redisPositionCacheAdapter = redisPositionCacheAdapter,
            killSwitchJpaRepository = killSwitchJpaRepository,
            auditTrailJpaRepository = auditTrailJpaRepository,
            magicjarMode = mode,
            magicjarAllowReal = allowReal,
            bootstrapOnStartup = bootstrapOnStartup,
            accountSnapshotCoordinatorProvider = accountSnapshotCoordinatorProvider,
        )

    private val args = DefaultApplicationArguments()

    @Test
    fun `활성 사용자 0명이면 idle return 하고 KIS 호출 없음`() {
        every { credentialRepository.findAllByAccountType("REAL") } returns emptyList()

        runner.run(args)

        // KIS 호출 없음 검증
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
    }

    @Test
    fun `KIS 호출 실패 시 KillSwitch auto-ON 후 다른 사용자 계속`() {
        val cred1Id = ConsumerUserKisCredentialId(userId = "11111111", accountType = "REAL")
        val cred1 =
            ConsumerUserKisCredentialJpaEntity(
                id = cred1Id,
                accountNumber = "11111111",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )
        val cred2Id = ConsumerUserKisCredentialId(userId = "22222222", accountType = "REAL")
        val cred2 =
            ConsumerUserKisCredentialJpaEntity(
                id = cred2Id,
                accountNumber = "22222222",
                appKeyEnc = ByteArray(0),
                secretKeyEnc = ByteArray(0),
            )

        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred1, cred2)

        // user1 KIS 호출 실패
        every { kisBalanceAdapter.fetchBalance("11111111", AccountType.REAL) } throws RuntimeException("KIS timeout")
        justRun { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        // user2 KIS 호출 성공
        val balance2 =
            KisBalance(
                userId = "22222222",
                accountType = AccountType.REAL,
                holdings =
                    listOf(
                        KisHolding(code = "005930", quantity = 10, avgPrice = BigDecimal("70000")),
                    ),
                fetchedAt = Instant.now(),
            )
        every { kisBalanceAdapter.fetchBalance("22222222", AccountType.REAL) } returns balance2
        justRun { instrumentJpaRepository.upsertStub(any()) }
        justRun { positionJpaRepository.upsert(any(), any(), any(), any()) }
        justRun { redisPositionCacheAdapter.putHoldings(any(), any(), any()) }

        runner.run(args)

        // user1 KillSwitch ON 검증
        verify(exactly = 1) { killSwitchJpaRepository.enableKillSwitch("11111111", any(), any()) }
        // user2 position upsert 검증 — 계속 진행
        verify(exactly = 1) { positionJpaRepository.upsert("22222222", "005930", 10, BigDecimal("70000")) }
    }

    // ── A 의제 hotfix (2026-05-20) — 3중 게이트 검증 ──

    @Test
    fun `A 의제 — magicjar_mode=MOCK 이면 Bootstrap skip (KIS 호출 0 + KillSwitch 0)`() {
        val mockModeRunner = newRunner(mode = "MOCK", allowReal = true, bootstrapOnStartup = true)

        mockModeRunner.run(args)

        // credentialRepository 호출 자체가 없음 (3중 게이트 검증 후 즉시 return)
        verify(exactly = 0) { credentialRepository.findAllByAccountType(any()) }
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
        verify(exactly = 0) { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
    }

    @Test
    fun `A 의제 — magicjar_allow-real=false 이면 Bootstrap skip (REAL 게이트 미충족)`() {
        val noAllowRealRunner = newRunner(mode = "REAL", allowReal = false, bootstrapOnStartup = true)

        noAllowRealRunner.run(args)

        verify(exactly = 0) { credentialRepository.findAllByAccountType(any()) }
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
        verify(exactly = 0) { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
    }

    @Test
    fun `A 의제 — bootstrap_position-on-startup=false 이면 Bootstrap skip (명시 비활성)`() {
        val disabledRunner = newRunner(mode = "REAL", allowReal = true, bootstrapOnStartup = false)

        disabledRunner.run(args)

        verify(exactly = 0) { credentialRepository.findAllByAccountType(any()) }
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
        verify(exactly = 0) { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
    }

    @Test
    fun `A 의제 — shouldRunBootstrap 3중 게이트 모두 true 일 때만 true 반환`() {
        // REAL + allowReal + bootstrapOnStartup 모두 true → true
        assert(newRunner("REAL", true, true).shouldRunBootstrap())
        // 셋 중 하나라도 false → false
        assert(!newRunner("MOCK", true, true).shouldRunBootstrap())
        assert(!newRunner("REAL", false, true).shouldRunBootstrap())
        assert(!newRunner("REAL", true, false).shouldRunBootstrap())
        // 대소문자 무관 (real == REAL)
        assert(newRunner("real", true, true).shouldRunBootstrap())
    }

    // ── B 의제 hotfix (2026-05-20) — retry 메커니즘 검증 ──

    /** KIS 500 응답 만들기 helper (WebClientResponseException) */
    private fun kis500(): WebClientResponseException =
        WebClientResponseException.create(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            org.springframework.http.HttpHeaders.EMPTY,
            ByteArray(0),
            null,
        )

    private fun kis401(): WebClientResponseException =
        WebClientResponseException.create(
            HttpStatus.UNAUTHORIZED.value(),
            "Unauthorized",
            org.springframework.http.HttpHeaders.EMPTY,
            ByteArray(0),
            null,
        )

    private fun activeCred(userId: String): ConsumerUserKisCredentialJpaEntity =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = userId, accountType = "REAL"),
            accountNumber = userId,
            appKeyEnc = ByteArray(0),
            secretKeyEnc = ByteArray(0),
        )

    private fun accountSnapshot(
        userId: String,
        holdingCode: String = "005930",
        quantity: Int = 7,
        avgPrice: BigDecimal = BigDecimal("70000"),
    ): KisAccountBalance {
        val now = Instant.now()
        return KisAccountBalance(
            userId = userId,
            accountType = AccountType.REAL,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal.ZERO,
                    cashD1 = BigDecimal.ZERO,
                    cashD2 = BigDecimal.ZERO,
                    totalEvaluation = BigDecimal.ZERO,
                    netAssetValue = BigDecimal.ZERO,
                    totalPurchaseAmount = BigDecimal.ZERO,
                    totalHoldingEvaluation = BigDecimal.ZERO,
                    totalProfitLoss = BigDecimal.ZERO,
                    fetchedAt = now,
                ),
            holdings =
                listOf(
                    AccountHolding(
                        code = holdingCode,
                        name = "삼성전자",
                        quantity = quantity,
                        avgPrice = avgPrice,
                        currentPrice = BigDecimal.ZERO,
                        evaluationAmount = BigDecimal.ZERO,
                        profitLoss = BigDecimal.ZERO,
                        profitLossRate = BigDecimal.ZERO,
                    ),
                ),
            fetchedAt = now,
        )
    }

    @Test
    fun `공통 계좌 snapshot coordinator가 있으면 Bootstrap 잔고 조회도 공통 관문을 사용한다`() {
        val cred = activeCred("66666666")
        val coordinator = mockk<AccountSnapshotCoordinator>()
        val coordinatorProvider = mockk<ObjectProvider<AccountSnapshotCoordinator>>()
        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred)
        every { coordinatorProvider.getIfAvailable() } returns coordinator
        every {
            coordinator.getSnapshot(
                "66666666",
                AccountType.REAL,
                "POSITION_BOOTSTRAP",
                false,
                AccountSnapshotCoordinator.QueryPath.USER_PATH,
            )
        } returns accountSnapshot("66666666")
        justRun { instrumentJpaRepository.upsertStub(any()) }
        justRun { positionJpaRepository.upsert(any(), any(), any(), any()) }
        justRun { redisPositionCacheAdapter.putHoldings(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        newRunner(
            mode = "REAL",
            allowReal = true,
            bootstrapOnStartup = true,
            accountSnapshotCoordinatorProvider = coordinatorProvider,
        ).run(args)

        verify(exactly = 1) {
            coordinator.getSnapshot(
                "66666666",
                AccountType.REAL,
                "POSITION_BOOTSTRAP",
                false,
                AccountSnapshotCoordinator.QueryPath.USER_PATH,
            )
        }
        verify(exactly = 0) { kisBalanceAdapter.fetchBalance(any(), any()) }
        verify(exactly = 1) { positionJpaRepository.upsert("66666666", "005930", 7, BigDecimal("70000")) }
    }

    @Test
    fun `B 의제 — KIS 500 1회 실패 + 2회째 성공 → Bootstrap 성공 (KillSwitch 0)`() {
        val cred = activeCred("33333333")
        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred)

        // 1st call: 500 throw, 2nd call: success
        val successBalance =
            KisBalance(
                userId = "33333333",
                accountType = AccountType.REAL,
                holdings =
                    listOf(
                        KisHolding(code = "005930", quantity = 5, avgPrice = BigDecimal("70000")),
                    ),
                fetchedAt = Instant.now(),
            )
        every { kisBalanceAdapter.fetchBalance("33333333", AccountType.REAL) } throws kis500() andThen successBalance
        justRun { instrumentJpaRepository.upsertStub(any()) }
        justRun { positionJpaRepository.upsert(any(), any(), any(), any()) }
        justRun { redisPositionCacheAdapter.putHoldings(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        runner.run(args)

        // 2회 호출됨 (1 실패 + 1 성공)
        verify(exactly = 2) { kisBalanceAdapter.fetchBalance("33333333", AccountType.REAL) }
        // KillSwitch ON 호출 0건
        verify(exactly = 0) { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
        // 정상 path → position upsert 호출됨
        verify(exactly = 1) { positionJpaRepository.upsert("33333333", "005930", 5, BigDecimal("70000")) }
    }

    @Test
    fun `B 의제 — KIS 500 maxAttempts 도달 → 최종 fail + KillSwitch ON (기존 동작 유지)`() {
        val cred = activeCred("44444444")
        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred)

        // 모든 시도 500 throw (총 4회 = 1 + 3 retry)
        every { kisBalanceAdapter.fetchBalance("44444444", AccountType.REAL) } throws kis500()
        justRun { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        runner.run(args)

        // 4회 호출됨 (1 + 3 retry)
        verify(exactly = 4) { kisBalanceAdapter.fetchBalance("44444444", AccountType.REAL) }
        // 최종 fail → KillSwitch ON (기존 동작 유지)
        verify(exactly = 1) { killSwitchJpaRepository.enableKillSwitch("44444444", "POSITION_BOOTSTRAP_FAILED", "consumer") }
    }

    @Test
    fun `B 의제 — KIS 401 (non-retryable) → retry 0 + 즉시 fail + KillSwitch ON`() {
        val cred = activeCred("55555555")
        every { credentialRepository.findAllByAccountType("REAL") } returns listOf(cred)

        every { kisBalanceAdapter.fetchBalance("55555555", AccountType.REAL) } throws kis401()
        justRun { killSwitchJpaRepository.enableKillSwitch(any(), any(), any()) }
        every { auditTrailJpaRepository.save(any()) } answers { firstArg() }

        runner.run(args)

        // 401 = non-retryable → 1회만 호출됨
        verify(exactly = 1) { kisBalanceAdapter.fetchBalance("55555555", AccountType.REAL) }
        // 즉시 fail → KillSwitch ON
        verify(exactly = 1) { killSwitchJpaRepository.enableKillSwitch("55555555", "POSITION_BOOTSTRAP_FAILED", "consumer") }
    }
}
