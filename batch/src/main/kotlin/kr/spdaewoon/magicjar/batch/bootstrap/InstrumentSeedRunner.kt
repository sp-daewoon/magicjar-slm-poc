package kr.spdaewoon.magicjar.batch.bootstrap

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentAliasJpaRepository
import kr.spdaewoon.magicjar.batch.persistence.BatchInstrumentJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * 정적 시드 로더 (T-IM1 PR-11a — ADR 0017 §2-5 정합).
 *
 * 시드 파일: batch/src/main/resources/instrument-seed.json
 * 시드 source: KRX 공시 KOSPI 200·KOSDAQ 100 구성 종목 + 주요 ETF 20
 *   기준일: 2026-05-07 (본 PR-11 발행 시점)
 *
 * 적용 시점:
 *   - DB instrument row count == 0 시 자동 시드 INSERT
 *   - KIS sync 실패 시 시드 그대로 유지 (마지막 성공 데이터 유지 원칙)
 *   - dev profile 첫 부팅 + KIS 키 미발급 환경에서도 자동완성 동작 보장
 *
 * Order(1) — InstrumentMasterSyncScheduler(boot) 보다 먼저 실행.
 */
@Component
@Order(1)
class InstrumentSeedRunner(
    private val instrumentRepo: BatchInstrumentJpaRepository,
    private val aliasRepo: BatchInstrumentAliasJpaRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.instrument.seed-path:instrument-seed.json}")
    private val seedPath: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(InstrumentSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val activeCount = instrumentRepo.countByActiveTrue()
        if (activeCount > 0) {
            log.info("[instrument-seed] DB에 종목 데이터 존재 ({}건) — 시드 skip", activeCount)
            return
        }

        log.info("[instrument-seed] DB 종목 0건 — 정적 시드 INSERT 시작")
        loadAndInsertSeed()
    }

    private fun loadAndInsertSeed() {
        val resource = ClassPathResource(seedPath)
        if (!resource.exists()) {
            log.warn("[instrument-seed] 시드 파일 없음: {} — skip", seedPath)
            return
        }

        val seeds: List<Map<String, Any?>> =
            resource.inputStream.use { stream ->
                objectMapper.readValue(stream, object : TypeReference<List<Map<String, Any?>>>() {})
            }

        var kospiCount = 0
        var kosdaqCount = 0
        var etfCount = 0
        var failCount = 0

        for (seed in seeds) {
            val code = seed["code"]?.toString() ?: continue
            val name = seed["name"]?.toString() ?: continue
            val market = seed["market"]?.toString() ?: "UNKNOWN"
            val type = seed["type"]?.toString() ?: "STOCK"
            val sector = seed["sector"]?.toString()

            try {
                upsertSeed(code = code, name = name, market = market, type = type, sector = sector)
                aliasRepo.upsert(code = code, alias = name, source = "KRX")

                when {
                    type == "ETF" || type == "ETN" -> etfCount++
                    market == "KOSPI" -> kospiCount++
                    market == "KOSDAQ" -> kosdaqCount++
                }
            } catch (e: Exception) {
                log.warn("[instrument-seed] INSERT 실패 code={} err={}", code, e.message)
                failCount++
            }
        }

        log.info(
            "[instrument-seed] 시드 INSERT 완료 — KOSPI {}건, KOSDAQ {}건, ETF {}건, 실패 {}건",
            kospiCount,
            kosdaqCount,
            etfCount,
            failCount,
        )
    }

    /**
     * PR-25 — 시드 INSERT 헬퍼 (PR-23 Repository wrapper가 Spring Data query inference에 걸려서 Runner 내부로 이동).
     * 시드 데이터에는 70+ 필드 정보 없음 → 모두 default(false/null)로 upsertFull 위임.
     */
    private fun upsertSeed(
        code: String,
        name: String,
        market: String,
        type: String,
        sector: String?,
    ) {
        instrumentRepo.upsertFull(
            code = code,
            isin = null,
            name = name,
            nameEn = null,
            market = market,
            type = type,
            sector = sector,
            listedAt = null,
            securitiesGroup = null,
            tradingHalt = false,
            liquidation = false,
            managementStock = false,
            marketAlert = null,
            marketAlertWarning = false,
            falseDisclosure = false,
            backdoorListing = false,
            shortOverheated = false,
            abnormalRunup = false,
            investmentCaution = false,
            exRightsType = null,
            faceValueChange = null,
            capitalIncrease = null,
            creditAble = false,
            creditDays = null,
            marginRate = null,
            shortOverheatedStatus = null,
            industryLarge = null,
            industryMedium = null,
            industrySmall = null,
            kospi200Sector = null,
            avlsScale = null,
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
            listedShares = null,
            capital = null,
            settlementMonth = null,
            publicOfferingPrice = null,
            marketCapPrev = null,
            salesAmount = null,
            operatingProfit = null,
            ordinaryProfit = null,
            netIncome = null,
            roe = null,
            financialBaseDate = null,
            creditLimitExceeded = false,
            collateralLendable = false,
            shortLendable = false,
        )
    }
}
