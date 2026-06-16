package kr.spdaewoon.magicjar.batch.job.instrument

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.batch.infrastructure.kis.KisInstrumentMasterAdapter
import kr.spdaewoon.magicjar.batch.infrastructure.kis.KisInstrumentRaw
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentStatusHistoryJpaEntity
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentStatusHistoryJpaRepository
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.KisInstrumentMasterPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.time.Instant

/**
 * InstrumentSyncJob smoke test (PR-21a 보강 — T-IM1 ADR 0017·0039).
 *
 * PR-21a 변경:
 *   - kisAdapter.fetchAllRaw() 경로로 diff·upsert (70+ 필드)
 *   - changed 판별 보강 (매매가드·securities_group)
 *
 * 검증:
 * 1. diff logic (INSERT NEW · UPDATE CHANGED · DELIST 폐지)
 * 2. delisted 처리 — markDelisted 호출 확인 (DELETE 금지 — ADR 0017 §2-2)
 * 3. KIS fetch 실패 → graceful (DB 변경 0건)
 */
class InstrumentSyncJobTest {
    private val kisPort: KisInstrumentMasterPort = mockk()
    private val kisAdapter: KisInstrumentMasterAdapter = mockk()
    private val instrumentRepo: BatchInstrumentJpaRepository = mockk()
    private val aliasRepo: BatchInstrumentAliasJpaRepository = mockk()
    private val statusHistoryRepo: BatchInstrumentStatusHistoryJpaRepository = mockk()
    private val redisTemplate: StringRedisTemplate = mockk()
    private val valueOps: ValueOperations<String, String> = mockk()
    private val kafkaTemplate: KafkaTemplate<String, String> = mockk()
    private val objectMapper = ObjectMapper()

    private lateinit var job: InstrumentSyncJob

    @BeforeEach
    fun setUp() {
        every { redisTemplate.opsForValue() } returns valueOps
        every { valueOps.setIfAbsent(any(), "true", Duration.ofSeconds(600)) } returns true
        every { redisTemplate.delete(any<String>()) } returns true
        every { redisTemplate.keys(any()) } returns emptySet()
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit

        // ADR 0090 Phase 1 — status history repo mock (active row 부재 default — INSERT만 호출)
        every { statusHistoryRepo.findActiveByCode(any()) } returns null
        every { statusHistoryRepo.closeActiveStatus(any(), any()) } returns 1
        every { statusHistoryRepo.save(any<BatchInstrumentStatusHistoryJpaEntity>()) } answers
            { firstArg() }

        job =
            InstrumentSyncJob(
                kisPort = kisPort,
                kisAdapter = kisAdapter,
                instrumentRepo = instrumentRepo,
                aliasRepo = aliasRepo,
                statusHistoryRepo = statusHistoryRepo,
                redisTemplate = redisTemplate,
                kafkaTemplate = kafkaTemplate,
                objectMapper = objectMapper,
            )
    }

    @Test
    fun `diff logic — INSERT NEW + DELIST 처리`() {
        // given
        val existingActive =
            listOf(
                makeEntity("005930", "삼성전자", "KOSPI"),
                makeEntity("000660", "SK하이닉스", "KOSPI"), // KIS 응답에서 제거 → DELIST
            )
        val kisRaws =
            listOf(
                makeRaw("005930", "삼성전자", InstrumentMarket.KOSPI),
                makeRaw("099999", "신규상장종목A", InstrumentMarket.KOSPI), // NEW
            )

        every { kisAdapter.fetchAllRaw() } returns kisRaws
        every { instrumentRepo.findAllByActiveTrue() } returns existingActive
        every {
            instrumentRepo.upsertFull(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns Unit
        justRun { aliasRepo.upsert(any(), any(), any()) }
        justRun { instrumentRepo.markDelisted(any(), any()) }
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns mockk { every { isDone } returns false }

        // when
        val result = job.run("test")

        // then
        assertEquals(2, result.fetched, "fetched = KIS 응답 건수")
        assertEquals(1, result.inserted, "신규 상장 1건 INSERT")
        // makeEntity securitiesGroup=null vs makeRaw securitiesGroup="ST" → changed=true → updated=1
        assertEquals(1, result.updated, "securitiesGroup 변동(null→ST) UPDATE 1건")
        assertEquals(1, result.delisted, "폐지 종목 1건 DELIST")
        assertEquals(0, result.failed, "실패 0건")

        // DELETE 금지 검증 — markDelisted 호출 확인
        verify(exactly = 1) { instrumentRepo.markDelisted("000660", any()) }
    }

    @Test
    fun `KIS fetch 실패 — graceful (DB 변경 0건 + failed=1)`() {
        // given
        every { kisAdapter.fetchAllRaw() } throws RuntimeException("KIS 종목 마스터 fetch 실패")

        // when
        val result = job.run("test")

        // then
        assertEquals(0, result.fetched, "fetch 실패 → fetched=0")
        assertEquals(1, result.failed, "failed=1 기록")
        assertEquals(0, result.inserted, "INSERT 없음")
        assertEquals(0, result.delisted, "DELIST 없음")

        // DB 미호출 검증
        verify(exactly = 0) { instrumentRepo.findAllByActiveTrue() }
        verify(exactly = 0) { instrumentRepo.markDelisted(any(), any()) }
    }

    // ─── Helper ──────────────────────────────────────────────────────────

    private fun makeEntity(
        code: String,
        name: String,
        market: String,
    ) = BatchInstrumentJpaEntity(
        code = code,
        name = name,
        market = market,
        type = "STOCK",
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun makeRaw(
        code: String,
        name: String,
        market: InstrumentMarket,
    ) = KisInstrumentRaw(
        code = code,
        isin = null,
        name = name,
        market = market,
        type = InstrumentType.STOCK,
        securitiesGroup = "ST",
        tradingHalt = false,
        liquidation = false,
        managementStock = false,
        marketAlert = "00",
        marketAlertWarning = false,
        falseDisclosure = false,
        backdoorListing = false,
        shortOverheated = false,
        abnormalRunup = false,
        investmentCaution = false,
        exRightsType = "00",
        faceValueChange = "00",
        capitalIncrease = "00",
        creditAble = false,
        creditDays = null,
        marginRate = null,
        shortOverheatedStatus = "0",
        avlsScale = null,
        industryLarge = null,
        industryMedium = null,
        industrySmall = null,
        kospi200Sector = null,
        isKospi100 = false,
        isKospi50 = false,
        isKospi200 = false,
        isKosdaq150 = false,
        isKrx = false,
        isKrx100 = false,
        isKrx300 = false,
        krxSectorCar = false,
        krxSectorSemicon = false,
        krxSectorBio = false,
        krxSectorBank = false,
        krxSectorEnergyChem = false,
        krxSectorSteel = false,
        krxSectorMediaComm = false,
        krxSectorConstruction = false,
        krxSectorSecurities = false,
        krxSectorShip = false,
        krxSectorInsurance = false,
        krxSectorTransport = false,
        isManufacturing = false,
        isLowLiquidity = false,
        isGovernanceIndex = false,
        isSpac = false,
        isVenture = false,
        isSriIndex = false,
        etpProductType = null,
        isElwPublisher = false,
        preferredStockType = null,
        groupCode = null,
        basePrice = null,
        tradingUnitNormal = null,
        tradingUnitOvertime = null,
        prevVolume = null,
        faceValue = null,
        listedAt = null,
        listedShares = null,
        capital = null,
        settlementMonth = null,
        publicOfferingPrice = null,
        salesAmount = null,
        operatingProfit = null,
        ordinaryProfit = null,
        netIncome = null,
        roe = null,
        financialBaseDate = null,
        marketCapPrev = null,
        creditLimitExceeded = false,
        collateralLendable = false,
        shortLendable = false,
    )
}
