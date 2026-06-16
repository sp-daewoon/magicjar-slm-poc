package kr.spdaewoon.magicjar.api.service.risk

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.api.persistence.position.PositionJpaAdapter
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.redis.RedisKeyRegistry
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import java.math.BigDecimal
import java.time.Instant as JavaInstant

/**
 * RiskUsageService 단위 테스트 — DTO 조립·capital unavailable 0 fabricate 금지 검증.
 *
 * position 산식 정확성은 domain RiskBudgetCalculatorTest 가 단일 진실원으로 검증하므로,
 * 여기서는 서비스가 그 결과를 올바르게 DTO 로 매핑하고, 소스 부재(capital cache miss·DLL 비활성·tradeFreq null)를
 * 올바르게 처리하는지에 집중한다.
 */
class RiskUsageServiceTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val fixedNow = Instant.fromEpochSeconds(1_736_640_000L)
    private val clock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private val userId = "user-A"

    private fun config(
        maxPositionAmount: BigDecimal = BigDecimal("10000000"),
        maxPositionPercent: BigDecimal = BigDecimal("10.00"),
        dllAmount: BigDecimal? = null,
        dllPercent: BigDecimal? = null,
        maxConcurrent: Int = 5,
        maxPerMinute: Int = 3,
        maxPerDay: Int = 50,
    ): RiskConfig =
        RiskConfig(
            userId = userId,
            maxPositionAmount = maxPositionAmount,
            maxPositionPercent = maxPositionPercent,
            dailyLossLimitAmount = dllAmount,
            dailyLossLimitPercent = dllPercent,
            maxConcurrentPositions = maxConcurrent,
            maxTradesPerMinute = maxPerMinute,
            maxTradesPerDay = maxPerDay,
            autoPauseThreshold = 10,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    private fun position(
        code: String,
        qty: Int,
        avg: BigDecimal,
    ): Position = Position(userId = userId, code = code, quantity = qty, avgPrice = avg, updatedAt = fixedNow)

    private fun balanceJson(
        netAsset: BigDecimal,
        cashD2: BigDecimal,
        cashBalance: BigDecimal = BigDecimal.ZERO,
    ): String {
        val snapshot =
            KisAccountBalance(
                userId = userId,
                accountType = AccountType.MOCK,
                balance =
                    AccountBalance(
                        cashBalance = cashBalance,
                        cashD1 = BigDecimal.ZERO,
                        cashD2 = cashD2,
                        totalEvaluation = netAsset,
                        netAssetValue = netAsset,
                        totalPurchaseAmount = BigDecimal.ZERO,
                        totalHoldingEvaluation = BigDecimal.ZERO,
                        totalProfitLoss = BigDecimal.ZERO,
                        fetchedAt = JavaInstant.ofEpochSecond(fixedNow.epochSeconds),
                    ),
                holdings = emptyList(),
                fetchedAt = JavaInstant.ofEpochSecond(fixedNow.epochSeconds),
            )
        return objectMapper.writeValueAsString(snapshot)
    }

    private fun buildService(
        config: RiskConfig,
        positions: List<Position>,
        balanceCacheJson: String?,
        dailyPnl: BigDecimal = BigDecimal.ZERO,
        minuteCount: Long = 0,
        dayCount: Long = 0,
    ): RiskUsageService {
        val riskConfigService = mockk<RiskConfigService>()
        every { riskConfigService.getOrDefault(userId) } returns config

        val positionAdapter = mockk<PositionJpaAdapter>()
        every { positionAdapter.findCurrentByUser(userId) } returns positions
        every { positionAdapter.sumDailyRealizedPnl(userId, any()) } returns dailyPnl

        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val zsetOps = mockk<ZSetOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zsetOps
        every { valueOps.get(RedisKeyRegistry.Account.balance(userId, AccountType.MOCK)) } returns balanceCacheJson
        every { zsetOps.count(any(), any<Double>(), any<Double>()) } answers {
            val key = firstArg<String>()
            if (key.contains("window:minute")) minuteCount else dayCount
        }

        return RiskUsageService(
            riskConfigService = riskConfigService,
            positionJpaAdapter = positionAdapter,
            redisTemplate = redis,
            objectMapper = objectMapper,
            magicjarMode = "MOCK",
            clock = clock,
        )
    }

    @Test
    fun `position 섹션 — DTO 예시값 그대로 (절대1천만·비율10퍼·순자산1억·노출900만·예수금300만 → buyable 100만)`() {
        val service =
            buildService(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("10.0")),
                positions = listOf(position("005930", 90, BigDecimal("100000"))), // 노출 900만
                balanceCacheJson = balanceJson(netAsset = BigDecimal("100000000"), cashD2 = BigDecimal("3000000")),
            )

        val pos = service.getUsage(userId, null).position
        pos.shouldNotBeNull()
        pos.absoluteLimit shouldBe BigDecimal("10000000")
        pos.percentLimit shouldBe BigDecimal("10000000")
        pos.percentBase shouldBe BigDecimal("100000000")
        pos.totalLimit shouldBe BigDecimal("10000000")
        pos.totalExposure shouldBe BigDecimal("9000000")
        pos.remaining shouldBe BigDecimal("1000000")
        pos.orderableCash shouldBe BigDecimal("3000000")
        pos.buyable shouldBe BigDecimal("1000000")
    }

    @Test
    fun `capital cache miss — position 섹션 부분 렌더 (config·노출은 채우고 순자산 의존 6필드만 null, 0 fabricate 금지)`() {
        val service =
            buildService(
                config = config(maxPositionAmount = BigDecimal("10000000"), maxPositionPercent = BigDecimal("10.00")),
                positions = listOf(position("005930", 10, BigDecimal("70000"))), // 노출 70만
                balanceCacheJson = null, // cache 비어있음 — KIS 미호출
            )

        val usage = service.getUsage(userId, null)
        val pos = usage.position
        // 섹션이 통째로 null 로 떨어지지 않고 부분 채움된다 (B 일일손실 카드 정합).
        pos.shouldNotBeNull()
        // config·positions 파생값은 capital 없어도 채워진다.
        pos.absoluteLimit shouldBe BigDecimal("10000000")
        pos.maxPositionPercent shouldBe BigDecimal("10.00")
        pos.totalExposure shouldBe BigDecimal("700000")
        // 순자산(capital) 의존 6필드는 null (0 fabricate 금지).
        pos.percentBase.shouldBeNull()
        pos.percentLimit.shouldBeNull()
        pos.totalLimit.shouldBeNull()
        pos.remaining.shouldBeNull()
        pos.orderableCash.shouldBeNull()
        pos.buyable.shouldBeNull()
        // 잔고와 무관한 섹션은 계속 채워진다.
        usage.concurrent.current shouldBe 1
    }

    @Test
    fun `orderableCash — cashD2 0 이면 cashBalance fallback`() {
        val service =
            buildService(
                config = config(maxPositionPercent = BigDecimal("100.00")),
                positions = emptyList(),
                balanceCacheJson =
                    balanceJson(netAsset = BigDecimal("100000000"), cashD2 = BigDecimal.ZERO, cashBalance = BigDecimal("2000000")),
            )
        service.getUsage(userId, null).position!!.orderableCash shouldBe BigDecimal("2000000")
    }

    @Test
    fun `dailyLoss amount — 한도10만·당일 -3만2천 → 여유6만8천`() {
        val service =
            buildService(
                config = config(dllAmount = BigDecimal("100000")),
                positions = emptyList(),
                balanceCacheJson = balanceJson(BigDecimal("100000000"), BigDecimal("1000000")),
                dailyPnl = BigDecimal("-32000"),
            )
        val dll = service.getUsage(userId, null).dailyLoss
        dll.enabled shouldBe true
        dll.limitAmount shouldBe BigDecimal("100000")
        dll.todayRealizedPnl shouldBe BigDecimal("-32000")
        dll.remaining shouldBe BigDecimal("68000")
    }

    @Test
    fun `dailyLoss percent — capital cache miss 면 remaining null (환산 불가)`() {
        val service =
            buildService(
                config = config(dllPercent = BigDecimal("3.00")),
                positions = emptyList(),
                balanceCacheJson = null, // capital 미상
                dailyPnl = BigDecimal("-10000"),
            )
        val dll = service.getUsage(userId, null).dailyLoss
        dll.enabled shouldBe true
        dll.limitPercent shouldBe BigDecimal("3.00")
        dll.remaining.shouldBeNull()
    }

    @Test
    fun `concurrent — 보유 종목 수와 남은 슬롯`() {
        val service =
            buildService(
                config = config(maxConcurrent = 5),
                positions =
                    listOf(
                        position("005930", 10, BigDecimal("70000")),
                        position("000660", 5, BigDecimal("100000")),
                        position("035720", 0, BigDecimal("50000")), // 청산 — 제외
                    ),
                balanceCacheJson = balanceJson(BigDecimal("100000000"), BigDecimal("1000000")),
            )
        val c = service.getUsage(userId, null).concurrent
        c.limit shouldBe 5
        c.current shouldBe 2
        c.remaining shouldBe 3
    }

    @Test
    fun `tradeFrequency — strategyId 지정 시 분당·일별 사용량`() {
        val service =
            buildService(
                config = config(maxPerMinute = 3, maxPerDay = 50),
                positions = emptyList(),
                balanceCacheJson = balanceJson(BigDecimal("100000000"), BigDecimal("1000000")),
                minuteCount = 1,
                dayCount = 38,
            )
        val tf = service.getUsage(userId, 86L).tradeFrequency
        tf.shouldNotBeNull()
        tf.strategyId shouldBe 86L
        tf.perMinuteLimit shouldBe 3
        tf.perMinuteUsed shouldBe 1
        tf.perDayLimit shouldBe 50
        tf.perDayUsed shouldBe 38
        tf.perDayRemaining shouldBe 12
    }

    @Test
    fun `tradeFrequency — strategyId 미지정이면 null`() {
        val service =
            buildService(
                config = config(),
                positions = emptyList(),
                balanceCacheJson = balanceJson(BigDecimal("100000000"), BigDecimal("1000000")),
            )
        service.getUsage(userId, null).tradeFrequency.shouldBeNull()
    }
}
