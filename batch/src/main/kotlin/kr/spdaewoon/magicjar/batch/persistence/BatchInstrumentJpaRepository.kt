package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * instrument Spring Data JPA Repository (batch 모듈 — T-IM1 sync 전용).
 *
 * PR-21a: upsert 쿼리 70+ 필드 보강.
 * KIS-first 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
 */
interface BatchInstrumentJpaRepository : JpaRepository<BatchInstrumentJpaEntity, String> {
    /**
     * 활성 종목 전체 조회 (sync diff 기준).
     */
    fun findAllByActiveTrue(): List<BatchInstrumentJpaEntity>

    /**
     * 코드로 단건 조회.
     */
    fun findByCode(code: String): BatchInstrumentJpaEntity?

    /**
     * instrument row 수 조회 (시드 fallback 판단용).
     */
    fun countByActiveTrue(): Long

    /**
     * ON CONFLICT upsert — 종목 마스터 INSERT 또는 UPDATE.
     * PR-21a: 70+ 필드 전체 매핑 (Tier 1·2·3·4).
     * DELIST 시 active=false, delisted_at=today — 다른 필드 변경 0 (감사 가능성 보전).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO instrument (
                code, isin, name, name_en, market, type, sector, active, listed_at, created_at, updated_at,
                securities_group,
                trading_halt, liquidation, management_stock, market_alert, market_alert_warning,
                false_disclosure, backdoor_listing, short_overheated, abnormal_runup, investment_caution,
                ex_rights_type, face_value_change, capital_increase, credit_able, credit_days,
                margin_rate, short_overheated_status,
                industry_large, industry_medium, industry_small, kospi200_sector, avls_scale,
                is_kospi100, is_kospi50, is_kospi200, is_kosdaq150, is_krx, is_krx100, is_krx300,
                krx_sector_car, krx_sector_semicon, krx_sector_bio, krx_sector_bank,
                krx_sector_energy_chem, krx_sector_steel, krx_sector_media_comm,
                krx_sector_construction, krx_sector_securities, krx_sector_ship,
                krx_sector_insurance, krx_sector_transport,
                is_manufacturing, is_low_liquidity, is_governance_index, is_spac, is_venture,
                is_sri_index, etp_product_type, is_elw_publisher, preferred_stock_type, group_code,
                base_price, trading_unit_normal, trading_unit_overtime, prev_volume, face_value,
                listed_shares, capital, settlement_month, public_offering_price, market_cap_prev,
                sales_amount, operating_profit, ordinary_profit, net_income, roe, financial_base_date,
                credit_limit_exceeded, collateral_lendable, short_lendable
            ) VALUES (
                :code, :isin, :name, :nameEn, :market, :type, :sector, true, :listedAt, now(), now(),
                :securitiesGroup,
                :tradingHalt, :liquidation, :managementStock, :marketAlert, :marketAlertWarning,
                :falseDisclosure, :backdoorListing, :shortOverheated, :abnormalRunup, :investmentCaution,
                :exRightsType, :faceValueChange, :capitalIncrease, :creditAble, :creditDays,
                :marginRate, :shortOverheatedStatus,
                :industryLarge, :industryMedium, :industrySmall, :kospi200Sector, :avlsScale,
                :isKospi100, :isKospi50, :isKospi200, :isKosdaq150, :isKrx, :isKrx100, :isKrx300,
                :krxSectorCar, :krxSectorSemicon, :krxSectorBio, :krxSectorBank,
                :krxSectorEnergyChem, :krxSectorSteel, :krxSectorMediaComm,
                :krxSectorConstruction, :krxSectorSecurities, :krxSectorShip,
                :krxSectorInsurance, :krxSectorTransport,
                :isManufacturing, :isLowLiquidity, :isGovernanceIndex, :isSpac, :isVenture,
                :isSriIndex, :etpProductType, :isElwPublisher, :preferredStockType, :groupCode,
                :basePrice, :tradingUnitNormal, :tradingUnitOvertime, :prevVolume, :faceValue,
                :listedShares, :capital, :settlementMonth, :publicOfferingPrice, :marketCapPrev,
                :salesAmount, :operatingProfit, :ordinaryProfit, :netIncome, :roe, :financialBaseDate,
                :creditLimitExceeded, :collateralLendable, :shortLendable
            )
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                market = EXCLUDED.market,
                type = EXCLUDED.type,
                sector = EXCLUDED.sector,
                active = true,
                listed_at = EXCLUDED.listed_at,
                securities_group = EXCLUDED.securities_group,
                trading_halt = EXCLUDED.trading_halt,
                liquidation = EXCLUDED.liquidation,
                management_stock = EXCLUDED.management_stock,
                market_alert = EXCLUDED.market_alert,
                market_alert_warning = EXCLUDED.market_alert_warning,
                false_disclosure = EXCLUDED.false_disclosure,
                backdoor_listing = EXCLUDED.backdoor_listing,
                short_overheated = EXCLUDED.short_overheated,
                abnormal_runup = EXCLUDED.abnormal_runup,
                investment_caution = EXCLUDED.investment_caution,
                ex_rights_type = EXCLUDED.ex_rights_type,
                face_value_change = EXCLUDED.face_value_change,
                capital_increase = EXCLUDED.capital_increase,
                credit_able = EXCLUDED.credit_able,
                credit_days = EXCLUDED.credit_days,
                margin_rate = EXCLUDED.margin_rate,
                short_overheated_status = EXCLUDED.short_overheated_status,
                industry_large = EXCLUDED.industry_large,
                industry_medium = EXCLUDED.industry_medium,
                industry_small = EXCLUDED.industry_small,
                kospi200_sector = EXCLUDED.kospi200_sector,
                avls_scale = EXCLUDED.avls_scale,
                is_kospi100 = EXCLUDED.is_kospi100,
                is_kospi50 = EXCLUDED.is_kospi50,
                is_kospi200 = EXCLUDED.is_kospi200,
                is_kosdaq150 = EXCLUDED.is_kosdaq150,
                is_krx = EXCLUDED.is_krx,
                is_krx100 = EXCLUDED.is_krx100,
                is_krx300 = EXCLUDED.is_krx300,
                krx_sector_car = EXCLUDED.krx_sector_car,
                krx_sector_semicon = EXCLUDED.krx_sector_semicon,
                krx_sector_bio = EXCLUDED.krx_sector_bio,
                krx_sector_bank = EXCLUDED.krx_sector_bank,
                krx_sector_energy_chem = EXCLUDED.krx_sector_energy_chem,
                krx_sector_steel = EXCLUDED.krx_sector_steel,
                krx_sector_media_comm = EXCLUDED.krx_sector_media_comm,
                krx_sector_construction = EXCLUDED.krx_sector_construction,
                krx_sector_securities = EXCLUDED.krx_sector_securities,
                krx_sector_ship = EXCLUDED.krx_sector_ship,
                krx_sector_insurance = EXCLUDED.krx_sector_insurance,
                krx_sector_transport = EXCLUDED.krx_sector_transport,
                is_manufacturing = EXCLUDED.is_manufacturing,
                is_low_liquidity = EXCLUDED.is_low_liquidity,
                is_governance_index = EXCLUDED.is_governance_index,
                is_spac = EXCLUDED.is_spac,
                is_venture = EXCLUDED.is_venture,
                is_sri_index = EXCLUDED.is_sri_index,
                etp_product_type = EXCLUDED.etp_product_type,
                is_elw_publisher = EXCLUDED.is_elw_publisher,
                preferred_stock_type = EXCLUDED.preferred_stock_type,
                group_code = EXCLUDED.group_code,
                base_price = EXCLUDED.base_price,
                trading_unit_normal = EXCLUDED.trading_unit_normal,
                trading_unit_overtime = EXCLUDED.trading_unit_overtime,
                prev_volume = EXCLUDED.prev_volume,
                face_value = EXCLUDED.face_value,
                listed_shares = EXCLUDED.listed_shares,
                capital = EXCLUDED.capital,
                settlement_month = EXCLUDED.settlement_month,
                public_offering_price = EXCLUDED.public_offering_price,
                market_cap_prev = EXCLUDED.market_cap_prev,
                sales_amount = EXCLUDED.sales_amount,
                operating_profit = EXCLUDED.operating_profit,
                ordinary_profit = EXCLUDED.ordinary_profit,
                net_income = EXCLUDED.net_income,
                roe = EXCLUDED.roe,
                financial_base_date = EXCLUDED.financial_base_date,
                credit_limit_exceeded = EXCLUDED.credit_limit_exceeded,
                collateral_lendable = EXCLUDED.collateral_lendable,
                short_lendable = EXCLUDED.short_lendable,
                updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertFull(
        code: String,
        isin: String?,
        name: String,
        nameEn: String?,
        market: String,
        type: String,
        sector: String?,
        listedAt: java.time.LocalDate?,
        securitiesGroup: String?,
        tradingHalt: Boolean,
        liquidation: Boolean,
        managementStock: Boolean,
        marketAlert: String?,
        marketAlertWarning: Boolean,
        falseDisclosure: Boolean,
        backdoorListing: Boolean,
        shortOverheated: Boolean,
        abnormalRunup: Boolean,
        investmentCaution: Boolean,
        exRightsType: String?,
        faceValueChange: String?,
        capitalIncrease: String?,
        creditAble: Boolean,
        creditDays: Short?,
        marginRate: Short?,
        shortOverheatedStatus: String?,
        industryLarge: String?,
        industryMedium: String?,
        industrySmall: String?,
        kospi200Sector: String?,
        avlsScale: String?,
        isKospi100: Boolean,
        isKospi50: Boolean,
        isKospi200: Boolean,
        isKosdaq150: Boolean,
        isKrx: Boolean,
        isKrx100: Boolean,
        isKrx300: Boolean,
        krxSectorCar: Boolean,
        krxSectorSemicon: Boolean,
        krxSectorBio: Boolean,
        krxSectorBank: Boolean,
        krxSectorEnergyChem: Boolean,
        krxSectorSteel: Boolean,
        krxSectorMediaComm: Boolean,
        krxSectorConstruction: Boolean,
        krxSectorSecurities: Boolean,
        krxSectorShip: Boolean,
        krxSectorInsurance: Boolean,
        krxSectorTransport: Boolean,
        isManufacturing: Boolean,
        isLowLiquidity: Boolean,
        isGovernanceIndex: Boolean,
        isSpac: Boolean,
        isVenture: Boolean,
        isSriIndex: Boolean,
        etpProductType: String?,
        isElwPublisher: Boolean,
        preferredStockType: String?,
        groupCode: String?,
        basePrice: java.math.BigDecimal?,
        tradingUnitNormal: Int?,
        tradingUnitOvertime: Int?,
        prevVolume: Long?,
        faceValue: java.math.BigDecimal?,
        listedShares: Long?,
        capital: java.math.BigDecimal?,
        settlementMonth: Short?,
        publicOfferingPrice: java.math.BigDecimal?,
        marketCapPrev: Long?,
        salesAmount: Long?,
        operatingProfit: Long?,
        ordinaryProfit: Long?,
        netIncome: Long?,
        roe: java.math.BigDecimal?,
        financialBaseDate: java.time.LocalDate?,
        creditLimitExceeded: Boolean,
        collateralLendable: Boolean,
        shortLendable: Boolean,
    )

    /**
     * 상장폐지 처리 — delisted_at UPDATE, active=false.
     * DELETE 금지 (ADR 0017 §2-2 — 생존자 편향 차단).
     * DELIST 시 다른 필드(매매가드 등) 변경 0 — 감사 가능성 보전.
     */
    @Modifying
    @Transactional
    @Query(
        value = "UPDATE instrument SET delisted_at = CAST(:delistedAt AS date), active = false, updated_at = now() WHERE code = :code",
        nativeQuery = true,
    )
    fun markDelisted(
        code: String,
        delistedAt: String,
    )
}
