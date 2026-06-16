package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.user.AccountType
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WatchlistIngestionWorker 단위 테스트.
 *
 * 핵심 검증:
 * - 부팅 시 watchlist 있으면 recompute(polling 등록) 호출 후 connect() 호출
 * - 부팅 시 watchlist 없으면 connect() 미호출
 * - recompute() → connect() 순서 (owner demand 재구독 핸드셰이크 트리거 전 polling 등록)
 * - [ADR 0077 J-1] REAL 자격증명 미등록 시 connect() skip + WARN 로그 (onStartup 사전 검증)
 *
 * ADR 0124 결정 2: watchlist는 WS 구독 0(polling 전용)이므로 recompute는 polling pool 등록만 한다.
 *   connect는 owner demand(STRATEGY/HOLDING 등) 재구독 핸드셰이크를 위해 선행 트리거한다.
 * ADR 0077 J-1: onStartup 시 REAL 자격증명 사전 검증 — 미등록 시 connect skip.
 */
class WatchlistIngestionWorkerTest {
    private val subscriptionRouter = mockk<SubscriptionRouter>(relaxed = true)
    private val pollingWorker = mockk<WatchlistPollingWorker>(relaxed = true)
    private val kisWsPort = mockk<KisWsPort>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>()
    private val marketSessionResolver = mockk<MarketSessionResolver>()
    private val credentialRepository = mockk<ConsumerUserKisCredentialJpaRepository>()

    private lateinit var worker: WatchlistIngestionWorker

    /** REAL 자격증명 mock 엔티티 (J-1 테스트용). */
    private val realCredentialEntity =
        ConsumerUserKisCredentialJpaEntity(
            id = ConsumerUserKisCredentialId(userId = "user001", accountType = AccountType.REAL.name),
            accountNumber = "12345678",
            appKeyEnc = ByteArray(0),
            secretKeyEnc = ByteArray(0),
        )

    @BeforeEach
    fun setUp() {
        worker =
            WatchlistIngestionWorker(
                subscriptionRouter = subscriptionRouter,
                pollingWorker = pollingWorker,
                kisWsPort = kisWsPort,
                watchlistRepository = watchlistRepository,
                marketSessionResolver = marketSessionResolver,
                credentialRepository = credentialRepository,
            )
    }

    @Test
    fun `watchlist 있으면 onStartup 시 recompute() 호출 후 connect() 호출`() {
        // given
        val entries =
            listOf(
                WatchlistEntry(userId = "user001", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)),
                WatchlistEntry(userId = "user001", code = "000660", priority = 200, createdAt = Instant.fromEpochSeconds(0)),
            )
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "test")
        // J-1: REAL 자격증명 존재 mock
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)

        // when
        worker.onStartup()

        // then — connect() 반드시 1회 호출
        verify(exactly = 1) { kisWsPort.connect() }
        // then — recompute() 호출 (user001 대상)
        verify { subscriptionRouter.recompute("user001") }
    }

    @Test
    fun `recompute() 는 connect() 보다 반드시 먼저 호출된다 — 1m stale race fix`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user002", code = "035720", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "test")
        // J-1: REAL 자격증명 존재 mock
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)

        // when
        worker.onStartup()

        // then — 순서 검증 (recompute → connect):
        //   recompute가 session.subscribedCodes를 전부 등록한 뒤 connect → onConnected 재구독이
        //   전체 종목을 스냅샷 → race로 인한 부분 재구독(watchlist 1m stale) 차단.
        verifyOrder {
            subscriptionRouter.recompute("user002")
            kisWsPort.connect()
        }
    }

    @Test
    fun `watchlist 없으면 connect() 미호출`() {
        // given — 빈 watchlist
        every { watchlistRepository.findAll() } returns emptyList()

        // when
        worker.onStartup()

        // then
        verify(exactly = 0) { kisWsPort.connect() }
        verify(exactly = 0) { subscriptionRouter.recompute(any()) }
    }

    @Test
    fun `장중(Regular) 세션이면 pollingWorker start() 호출`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user003", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Regular
        // J-1: REAL 자격증명 존재 mock
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)

        // when
        worker.onStartup()

        // then
        verify { pollingWorker.start() }
    }

    @Test
    fun `장마감(Closed) 세션이면 pollingWorker start() 미호출`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user004", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "closed")
        // J-1: REAL 자격증명 존재 mock
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns listOf(realCredentialEntity)

        // when
        worker.onStartup()

        // then
        verify(exactly = 0) { pollingWorker.start() }
    }

    /**
     * [ADR 0077 J-1] REAL 자격증명 미등록 시 connect() skip.
     *
     * J-1 fix 검증:
     * - onStartup() 진입 시 REAL 자격증명 사전 검증
     * - credentialRepository.findAllByAccountType(REAL) → 빈 리스트 → connect() skip
     * - WARN 로그 1회 + scheduleReconnect 차단 (KIS WS 무한 retry 방지)
     * - 이전 결함: 자격증명 없어도 connect() 호출 → ERROR_APPROVAL_KEY → OPSP8996 86건/h evidence
     *
     * ADR 0077 §4-4: "자격증명 0건이면 connect 자체 skip → ERROR_APPROVAL_KEY 문자열 발사 차단"
     */
    @Test
    fun `J-1 REAL 자격증명 미등록 시 onStartup connect 미호출 — OPSP8996 무한루프 방지`() {
        // given: watchlist 있음 (users 존재)
        val entries =
            listOf(
                WatchlistEntry(userId = "user001", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)),
            )
        every { watchlistRepository.findAll() } returns entries
        // given: REAL 자격증명 미등록 (빈 리스트)
        every { credentialRepository.findAllByAccountType(AccountType.REAL.name) } returns emptyList()

        // when
        worker.onStartup()

        // then: connect() 미호출 (J-1 사전 검증으로 차단)
        verify(exactly = 0) { kisWsPort.connect() }
        // then: recompute() 미호출 (connect 차단 → subscribe 불필요)
        verify(exactly = 0) { subscriptionRouter.recompute(any()) }
    }
}
