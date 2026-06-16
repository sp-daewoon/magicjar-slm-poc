package kr.spdaewoon.magicjar.api.persistence

import kotlinx.datetime.toKotlinLocalDate
import kr.spdaewoon.magicjar.domain.instrument.Instrument
import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentSearchResult
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import kr.spdaewoon.magicjar.domain.instrument.port.InstrumentRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * InstrumentRepository 도메인 포트 구현 (api 모듈).
 * PR-11b 신규: InstrumentJpaEntity ↔ Instrument 도메인 변환.
 * PR-21b 보강: findActiveTradeable·countByMarket·findByMarketCapDesc·findByKospi200 implement.
 */
@Component
class InstrumentRepositoryAdapter(
    private val instrumentJpaRepository: InstrumentJpaRepository,
) : InstrumentRepository {
    private val log = LoggerFactory.getLogger(InstrumentRepositoryAdapter::class.java)

    @Transactional(readOnly = true)
    override fun findByCode(code: String): Instrument? = instrumentJpaRepository.findById(code).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun findActiveByMarket(market: InstrumentMarket): List<Instrument> =
        instrumentJpaRepository.findByActiveTrueAndMarket(market.name).map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findByActiveTrue(): List<Instrument> = instrumentJpaRepository.findByActiveTrue().map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findActiveByCodeIn(codes: Collection<String>): List<Instrument> =
        instrumentJpaRepository.findByActiveTrueAndCodeIn(codes).map { it.toDomain() }

    @Transactional
    override fun markDelisted(
        code: String,
        delistedAt: LocalDate,
    ) {
        instrumentJpaRepository.markDelisted(code, delistedAt)
        log.info("[instrument] markDelisted: code={} delistedAt={}", code, delistedAt)
    }

    @Transactional(readOnly = true)
    override fun searchByPgTrgm(
        query: String,
        limit: Int,
        market: InstrumentMarket?,
    ): List<InstrumentSearchResult> {
        val pageable = PageRequest.of(0, limit)
        val entities = instrumentJpaRepository.searchByNameOrCode(query, market?.name, pageable)
        return entities.mapIndexed { idx, e ->
            // score: 앞쪽 결과일수록 높은 점수 (1.0 ~ 0.1 범위)
            val score = maxOf(1.0 - idx * 0.05, 0.1)
            InstrumentSearchResult(
                code = e.code,
                name = e.name,
                market = runCatching { InstrumentMarket.valueOf(e.market) }.getOrElse { InstrumentMarket.KOSPI },
                sector = e.sector,
                score = score,
            )
        }
    }

    @Transactional
    override fun upsert(instrument: Instrument): Instrument {
        val existing = instrumentJpaRepository.findById(instrument.code).orElse(null)
        val entity =
            if (existing != null) {
                // UPDATE: name·market·sector·nameNorm·theme·active 갱신 (70 신규 필드는 market-data sync job이 담당)
                InstrumentJpaEntity(
                    code = instrument.code,
                    isin = instrument.isin,
                    name = instrument.name,
                    nameEn = instrument.nameEn,
                    market = instrument.market.name,
                    type = instrument.type.name,
                    sector = instrument.sector,
                    nameNorm = normalizeNameForIndex(instrument.name),
                    listedAt = instrument.listedAt?.toJavaLocalDate(),
                    delistedAt = instrument.delistedAt?.toJavaLocalDate(),
                    active = instrument.active,
                    createdAt = existing.createdAt,
                    updatedAt = Instant.now(),
                    // 기존 Tier 2~4 필드 보존 (sync job이 갱신 — upsert는 도메인 stub 경로)
                    tradingHalt = existing.tradingHalt,
                    liquidation = existing.liquidation,
                    managementStock = existing.managementStock,
                    marketAlert = existing.marketAlert,
                    marketAlertWarning = existing.marketAlertWarning,
                    falseDisclosure = existing.falseDisclosure,
                    backdoorListing = existing.backdoorListing,
                    shortOverheated = existing.shortOverheated,
                    abnormalRunup = existing.abnormalRunup,
                    investmentCaution = existing.investmentCaution,
                    exRightsType = existing.exRightsType,
                    faceValueChange = existing.faceValueChange,
                    capitalIncrease = existing.capitalIncrease,
                    creditAble = existing.creditAble,
                    creditDays = existing.creditDays,
                    marginRate = existing.marginRate,
                    shortOverheatedStatus = existing.shortOverheatedStatus,
                    securitiesGroup = existing.securitiesGroup,
                    industryLarge = existing.industryLarge,
                    industryMedium = existing.industryMedium,
                    industrySmall = existing.industrySmall,
                    kospi200Sector = existing.kospi200Sector,
                    avlsScale = existing.avlsScale,
                    isKospi100 = existing.isKospi100,
                    isKospi50 = existing.isKospi50,
                    isKospi200 = existing.isKospi200,
                    isKosdaq150 = existing.isKosdaq150,
                    isKrx = existing.isKrx,
                    isKrx100 = existing.isKrx100,
                    isKrx300 = existing.isKrx300,
                    krxSectorCar = existing.krxSectorCar,
                    krxSectorSemicon = existing.krxSectorSemicon,
                    krxSectorBio = existing.krxSectorBio,
                    krxSectorBank = existing.krxSectorBank,
                    krxSectorEnergyChem = existing.krxSectorEnergyChem,
                    krxSectorSteel = existing.krxSectorSteel,
                    krxSectorMediaComm = existing.krxSectorMediaComm,
                    krxSectorConstruction = existing.krxSectorConstruction,
                    krxSectorSecurities = existing.krxSectorSecurities,
                    krxSectorShip = existing.krxSectorShip,
                    krxSectorInsurance = existing.krxSectorInsurance,
                    krxSectorTransport = existing.krxSectorTransport,
                    isManufacturing = existing.isManufacturing,
                    isLowLiquidity = existing.isLowLiquidity,
                    isGovernanceIndex = existing.isGovernanceIndex,
                    isSpac = existing.isSpac,
                    isVenture = existing.isVenture,
                    isSriIndex = existing.isSriIndex,
                    etpProductType = existing.etpProductType,
                    isElwPublisher = existing.isElwPublisher,
                    preferredStockType = existing.preferredStockType,
                    groupCode = existing.groupCode,
                    basePrice = existing.basePrice,
                    tradingUnitNormal = existing.tradingUnitNormal,
                    tradingUnitOvertime = existing.tradingUnitOvertime,
                    prevVolume = existing.prevVolume,
                    faceValue = existing.faceValue,
                    listedShares = existing.listedShares,
                    capital = existing.capital,
                    settlementMonth = existing.settlementMonth,
                    publicOfferingPrice = existing.publicOfferingPrice,
                    marketCapPrev = existing.marketCapPrev,
                    salesAmount = existing.salesAmount,
                    operatingProfit = existing.operatingProfit,
                    ordinaryProfit = existing.ordinaryProfit,
                    netIncome = existing.netIncome,
                    roe = existing.roe,
                    financialBaseDate = existing.financialBaseDate,
                    creditLimitExceeded = existing.creditLimitExceeded,
                    collateralLendable = existing.collateralLendable,
                    shortLendable = existing.shortLendable,
                )
            } else {
                InstrumentJpaEntity(
                    code = instrument.code,
                    isin = instrument.isin,
                    name = instrument.name,
                    nameEn = instrument.nameEn,
                    market = instrument.market.name,
                    type = instrument.type.name,
                    sector = instrument.sector,
                    nameNorm = normalizeNameForIndex(instrument.name),
                    listedAt = instrument.listedAt?.toJavaLocalDate(),
                    delistedAt = instrument.delistedAt?.toJavaLocalDate(),
                    active = instrument.active,
                )
            }
        return instrumentJpaRepository.save(entity).toDomain()
    }

    @Transactional
    override fun upsertAll(instruments: List<Instrument>): Int {
        if (instruments.isEmpty()) return 0
        var count = 0
        for (instrument in instruments) {
            upsert(instrument)
            count++
        }
        log.debug("[instrument] upsertAll: count={}", count)
        return count
    }

    // ==========================================================================
    // PR-21b 신규 메서드 구현
    // ==========================================================================

    @Transactional(readOnly = true)
    override fun findActiveTradeable(): List<Instrument> = instrumentJpaRepository.findActiveTradeable().map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun countByMarket(): Map<String, Long> {
        val rows = instrumentJpaRepository.countByMarket()
        return rows.associate { row ->
            val market = row[0] as String
            val count =
                when (val raw = row[1]) {
                    is Long -> raw
                    is Number -> raw.toLong()
                    else -> 0L
                }
            market to count
        }
    }

    @Transactional(readOnly = true)
    override fun findByMarketCapDesc(limit: Int): List<Instrument> {
        val pageable = PageRequest.of(0, limit)
        return instrumentJpaRepository.findByMarketCapDescLimit(pageable).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun findByKospi200(): List<Instrument> = instrumentJpaRepository.findByActiveTrueAndIsKospi200True().map { it.toDomain() }

    @Transactional(readOnly = true)
    override fun findActiveStocks(): List<Instrument> =
        instrumentJpaRepository.findByActiveTrueAndSecuritiesGroup("ST").map { it.toDomain() }

    // ── private helpers ─────────────────────────────────────────────────

    /** 종목명 정규화 — 공백·점 제거 + lowercase. design §6-5. */
    private fun normalizeNameForIndex(name: String): String = name.replace(Regex("[\\s.·()]"), "").lowercase()

    private fun InstrumentJpaEntity.toDomain(): Instrument =
        Instrument(
            code = code,
            isin = isin,
            name = name,
            nameEn = nameEn,
            market = runCatching { InstrumentMarket.valueOf(market) }.getOrElse { InstrumentMarket.KOSPI },
            type = runCatching { InstrumentType.valueOf(type) }.getOrElse { InstrumentType.STOCK },
            sector = sector,
            listedAt = listedAt?.toKotlinLocalDate(),
            delistedAt = delistedAt?.toKotlinLocalDate(),
            active = active,
        )

    private fun kotlinx.datetime.LocalDate.toJavaLocalDate(): LocalDate = LocalDate.of(year, monthNumber, dayOfMonth)
}
