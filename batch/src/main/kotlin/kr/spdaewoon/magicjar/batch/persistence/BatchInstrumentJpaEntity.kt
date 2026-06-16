package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * instrument JPA 엔티티 (batch 모듈 — T-IM1 sync 전용).
 *
 * PR-21a: V5 70 컬럼 add-only 보강. 기존 14 필드 변경 0 (PR-11a~PR-20 가동 코드 호환).
 * api 모듈 InstrumentJpaEntity와 동일 테이블 — 컬럼명 1:1 정합 의무.
 * batch 모듈은 sync write 전용으로 분리 관리 (모듈 경계 — ADR 0004).
 *
 * KIS-first 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
 * 컬럼 정의: _workspace/specs/pr-21-instrument-kis-first-design.md §3·§5
 */
@Entity
@Table(name = "instrument")
class BatchInstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "isin", length = 12)
    val isin: String? = null,
    @Column(name = "name", length = 80, nullable = false)
    var name: String,
    @Column(name = "name_en", length = 120)
    var nameEn: String? = null,
    @Column(name = "market", length = 10, nullable = false)
    var market: String,
    @Column(name = "type", length = 20, nullable = false)
    var type: String,
    @Column(name = "sector", length = 40)
    var sector: String? = null,
    @Column(name = "name_norm", length = 80)
    var nameNorm: String? = null,
    @Column(name = "theme", length = 200)
    var theme: String? = null,
    @Column(name = "active", nullable = false)
    var active: Boolean = true,
    @Column(name = "listed_at")
    var listedAt: LocalDate? = null,
    @Column(name = "delisted_at")
    var delistedAt: LocalDate? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    // =========================================================================
    // PR-21a 신규: Tier 1 보강 — 1 컬럼
    // KIS scrt_grp_cls_code — ST/MF/RT/SC/IF/DR/EW/EF/SW/SR/BC/FE/FS
    // =========================================================================
    @Column(name = "securities_group", length = 2)
    var securitiesGroup: String? = null,
    // =========================================================================
    // PR-21a 신규: Tier 2 매매 가드 — 17 컬럼 (자동매매 본체 필수)
    // =========================================================================
    @Column(name = "trading_halt", nullable = false)
    var tradingHalt: Boolean = false, // trht_yn
    @Column(name = "liquidation", nullable = false)
    var liquidation: Boolean = false, // sltr_yn
    @Column(name = "management_stock", nullable = false)
    var managementStock: Boolean = false, // mang_issu_yn
    @Column(name = "market_alert", length = 2)
    var marketAlert: String? = null, // mrkt_alrm_cls_code (00/01/02/03)
    @Column(name = "market_alert_warning", nullable = false)
    var marketAlertWarning: Boolean = false, // mrkt_alrm_risk_adnt_yn
    @Column(name = "false_disclosure", nullable = false)
    var falseDisclosure: Boolean = false, // insn_pbnt_yn
    @Column(name = "backdoor_listing", nullable = false)
    var backdoorListing: Boolean = false, // byps_lstn_yn
    @Column(name = "short_overheated", nullable = false)
    var shortOverheated: Boolean = false, // ssts_hot_yn
    @Column(name = "abnormal_runup", nullable = false)
    var abnormalRunup: Boolean = false, // stange_runup_yn
    @Column(name = "investment_caution", nullable = false)
    var investmentCaution: Boolean = false, // invt_alrm_yn (KOSDAQ 단독)
    @Column(name = "ex_rights_type", length = 2)
    var exRightsType: String? = null, // flng_cls_code
    @Column(name = "face_value_change", length = 2)
    var faceValueChange: String? = null, // fcam_mod_cls_code
    @Column(name = "capital_increase", length = 2)
    var capitalIncrease: String? = null, // icic_cls_code
    @Column(name = "credit_able", nullable = false)
    var creditAble: Boolean = false, // crdt_able
    @Column(name = "credit_days")
    var creditDays: Short? = null, // crdt_days
    @Column(name = "margin_rate")
    var marginRate: Short? = null, // marg_rate
    @Column(name = "short_overheated_status", length = 1)
    var shortOverheatedStatus: String? = null, // short_over_cls_code
    // =========================================================================
    // PR-21a 신규: Tier 3 분류 인덱스 — 33 컬럼 (screening·universe)
    // =========================================================================
    @Column(name = "industry_large", length = 4)
    var industryLarge: String? = null, // bstp_larg_div_code
    @Column(name = "industry_medium", length = 4)
    var industryMedium: String? = null, // bstp_medm_div_code
    @Column(name = "industry_small", length = 4)
    var industrySmall: String? = null, // bstp_smal_div_code
    @Column(name = "kospi200_sector", length = 1)
    var kospi200Sector: String? = null, // kospi200_apnt_cls_code (KOSPI만)
    @Column(name = "avls_scale", length = 1)
    var avlsScale: String? = null, // avls_scal_cls_code
    @Column(name = "is_kospi100", nullable = false)
    var isKospi100: Boolean = false, // kospi100_issu_yn (KOSPI만)
    @Column(name = "is_kospi50", nullable = false)
    var isKospi50: Boolean = false, // kospi50_issu_yn (KOSPI만)
    @Column(name = "is_kospi200", nullable = false)
    var isKospi200: Boolean = false, // derived: kospi200_apnt_cls_code != '0'
    @Column(name = "is_kosdaq150", nullable = false)
    var isKosdaq150: Boolean = false, // ksq150_nmix_yn (KOSDAQ만)
    @Column(name = "is_krx", nullable = false)
    var isKrx: Boolean = false, // krx_issu_yn
    @Column(name = "is_krx100", nullable = false)
    var isKrx100: Boolean = false, // krx100_issu_yn
    @Column(name = "is_krx300", nullable = false)
    var isKrx300: Boolean = false, // krx300_issu_yn
    @Column(name = "krx_sector_car", nullable = false)
    var krxSectorCar: Boolean = false, // krx_car_yn
    @Column(name = "krx_sector_semicon", nullable = false)
    var krxSectorSemicon: Boolean = false, // krx_smcn_yn
    @Column(name = "krx_sector_bio", nullable = false)
    var krxSectorBio: Boolean = false, // krx_bio_yn
    @Column(name = "krx_sector_bank", nullable = false)
    var krxSectorBank: Boolean = false, // krx_bank_yn
    @Column(name = "krx_sector_energy_chem", nullable = false)
    var krxSectorEnergyChem: Boolean = false, // krx_enrg_chms_yn
    @Column(name = "krx_sector_steel", nullable = false)
    var krxSectorSteel: Boolean = false, // krx_stel_yn
    @Column(name = "krx_sector_media_comm", nullable = false)
    var krxSectorMediaComm: Boolean = false, // krx_medi_cmnc_yn
    @Column(name = "krx_sector_construction", nullable = false)
    var krxSectorConstruction: Boolean = false, // krx_cnst_yn
    @Column(name = "krx_sector_securities", nullable = false)
    var krxSectorSecurities: Boolean = false, // krx_scrt_yn
    @Column(name = "krx_sector_ship", nullable = false)
    var krxSectorShip: Boolean = false, // krx_ship_yn
    @Column(name = "krx_sector_insurance", nullable = false)
    var krxSectorInsurance: Boolean = false, // krx_insu_yn
    @Column(name = "krx_sector_transport", nullable = false)
    var krxSectorTransport: Boolean = false, // krx_trnp_yn
    @Column(name = "is_manufacturing", nullable = false)
    var isManufacturing: Boolean = false, // mnin_cls_code_yn (KOSPI만)
    @Column(name = "is_low_liquidity", nullable = false)
    var isLowLiquidity: Boolean = false, // low_current_yn
    @Column(name = "is_governance_index", nullable = false)
    var isGovernanceIndex: Boolean = false, // sprn_strr_nmix_issu_yn (KOSPI만)
    @Column(name = "is_spac", nullable = false)
    var isSpac: Boolean = false, // etpr_undt_objt_co_yn
    @Column(name = "is_venture", nullable = false)
    var isVenture: Boolean = false, // vntr_issu_yn (KOSDAQ만)
    @Column(name = "is_sri_index", nullable = false)
    var isSriIndex: Boolean = false, // sri_nmix_yn (KOSPI만)
    @Column(name = "etp_product_type", length = 1)
    var etpProductType: String? = null, // etp_prod_cls_code
    @Column(name = "is_elw_publisher", nullable = false)
    var isElwPublisher: Boolean = false, // elw_pblc_yn (KOSPI만)
    @Column(name = "preferred_stock_type", length = 1)
    var preferredStockType: String? = null, // prst_cls_code
    @Column(name = "group_code", length = 3)
    var groupCode: String? = null, // grp_code
    // =========================================================================
    // PR-21a 신규: Tier 4 시장 메타 + 재무 — 19 컬럼
    // =========================================================================
    @Column(name = "base_price", precision = 15, scale = 0)
    var basePrice: BigDecimal? = null, // stck_sdpr
    @Column(name = "trading_unit_normal")
    var tradingUnitNormal: Int? = null, // frml_mrkt_deal_qty_unit
    @Column(name = "trading_unit_overtime")
    var tradingUnitOvertime: Int? = null, // ovtm_mrkt_deal_qty_unit
    @Column(name = "prev_volume")
    var prevVolume: Long? = null, // prdy_vol
    @Column(name = "face_value", precision = 15, scale = 0)
    var faceValue: BigDecimal? = null, // stck_fcam
    @Column(name = "listed_shares")
    var listedShares: Long? = null, // lstn_stcn (천 단위)
    @Column(name = "capital", precision = 25, scale = 0)
    var capital: BigDecimal? = null, // cpfn
    @Column(name = "settlement_month")
    var settlementMonth: Short? = null, // stac_month
    @Column(name = "public_offering_price", precision = 10, scale = 0)
    var publicOfferingPrice: BigDecimal? = null, // po_prc
    @Column(name = "market_cap_prev")
    var marketCapPrev: Long? = null, // prdy_avls_scal (억)
    @Column(name = "sales_amount")
    var salesAmount: Long? = null, // sale_account (억)
    @Column(name = "operating_profit")
    var operatingProfit: Long? = null, // bsop_prfi (억)
    @Column(name = "ordinary_profit")
    var ordinaryProfit: Long? = null, // op_prfi (억)
    @Column(name = "net_income")
    var netIncome: Long? = null, // thtr_ntin (억)
    @Column(name = "roe", precision = 7, scale = 2)
    var roe: BigDecimal? = null, // roe (음수 가능)
    @Column(name = "financial_base_date")
    var financialBaseDate: LocalDate? = null, // base_date
    @Column(name = "credit_limit_exceeded", nullable = false)
    var creditLimitExceeded: Boolean = false, // co_crdt_limt_over_yn
    @Column(name = "collateral_lendable", nullable = false)
    var collateralLendable: Boolean = false, // secu_lend_able_yn
    @Column(name = "short_lendable", nullable = false)
    var shortLendable: Boolean = false, // stln_able_yn
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}
