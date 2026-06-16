package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * instrument JPA 엔티티 (ADR 0017).
 * V3 CHECK 완화로 market='UNKNOWN', type='UNKNOWN' stub row 허용.
 *
 * PR-11b 보강: name_norm·theme 컬럼 추가 (Flyway V4 대응).
 * PR-21b 보강: KIS-first 정정 사이클 (사용자 비판 응답).
 *   신규 70 필드 add-only (기존 14 필드 변경 0 — PR-11~PR-20 코드 호환).
 *   Tier 1(1) + Tier 2(17) + Tier 3(33) + Tier 4(19) = 70 신규 컬럼.
 *   권위 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
 *   Flyway V5 ALTER 후 활성화 (V5는 PR-21a market-data 단독 발행).
 */
@Entity
@Table(name = "instrument")
class InstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "isin", length = 12)
    val isin: String? = null,
    @Column(name = "name", length = 80, nullable = false)
    val name: String,
    @Column(name = "name_en", length = 120)
    val nameEn: String? = null,
    @Column(name = "market", length = 10, nullable = false)
    val market: String,
    @Column(name = "type", length = 20, nullable = false)
    val type: String,
    @Column(name = "sector", length = 40)
    val sector: String? = null,
    /** 정규화된 종목명 (공백·점 제거 + lowercase) — V4 gin_trgm 인덱스 대상. */
    @Column(name = "name_norm", length = 80)
    val nameNorm: String? = null,
    /** 테마 (쉼표 구분 — 예: "반도체,스마트폰,IT") — V4 추가. */
    @Column(name = "theme", length = 200)
    val theme: String? = null,
    @Column(name = "listed_at")
    val listedAt: LocalDate? = null,
    @Column(name = "delisted_at")
    var delistedAt: LocalDate? = null,
    @Column(name = "active", nullable = false)
    var active: Boolean = true,
    /** [Tier 1] KIS 증권그룹구분코드 (scrt_grp_cls_code). type 매핑의 권위 source. */
    @Column(name = "securities_group", length = 2)
    var securitiesGroup: String? = null,
    /** [Tier 2 매매가드] 거래정지 여부 (trht_yn Y/N → Boolean). */
    @Column(name = "trading_halt", nullable = false)
    var tradingHalt: Boolean = false,
    /** 정리매매 여부 (sltr_yn). true 시 매수 차단. */
    @Column(name = "liquidation", nullable = false)
    var liquidation: Boolean = false,
    /** 관리종목 여부 (mang_issu_yn). */
    @Column(name = "management_stock", nullable = false)
    var managementStock: Boolean = false,
    /** 시장경고 코드 (mrkt_alrm_cls_code). 00:해당없음 01:투자주의 02:투자경고 03:투자위험. */
    @Column(name = "market_alert", length = 2)
    var marketAlert: String? = null,
    /** 시장경고위험 예고 (mrkt_alrm_risk_adnt_yn). */
    @Column(name = "market_alert_warning", nullable = false)
    var marketAlertWarning: Boolean = false,
    /** 불성실공시 여부 (insn_pbnt_yn). */
    @Column(name = "false_disclosure", nullable = false)
    var falseDisclosure: Boolean = false,
    /** 우회상장 여부 (byps_lstn_yn). */
    @Column(name = "backdoor_listing", nullable = false)
    var backdoorListing: Boolean = false,
    /** 공매도 과열 (ssts_hot_yn). */
    @Column(name = "short_overheated", nullable = false)
    var shortOverheated: Boolean = false,
    /** 이상급등 (stange_runup_yn). */
    @Column(name = "abnormal_runup", nullable = false)
    var abnormalRunup: Boolean = false,
    /** 투자주의환기종목 (invt_alrm_yn) — KOSDAQ 단독, KOSPI는 false. */
    @Column(name = "investment_caution", nullable = false)
    var investmentCaution: Boolean = false,
    /** 락구분 (flng_cls_code). 00:해당없음 01:권리락 02:배당락 등. */
    @Column(name = "ex_rights_type", length = 2)
    var exRightsType: String? = null,
    /** 액면가변경 (fcam_mod_cls_code). 00:해당없음 01:액면분할 02:액면병합. */
    @Column(name = "face_value_change", length = 2)
    var faceValueChange: String? = null,
    /** 증자구분 (icic_cls_code). 00:해당없음 01:유상 02:무상 03:유무상. */
    @Column(name = "capital_increase", length = 2)
    var capitalIncrease: String? = null,
    /** 신용주문 가능 (crdt_able). */
    @Column(name = "credit_able", nullable = false)
    var creditAble: Boolean = false,
    /** 신용기간 일수 (crdt_days 3자리). */
    @Column(name = "credit_days")
    var creditDays: Short? = null,
    /** 증거금 비율 % (marg_rate 3자리 정수). */
    @Column(name = "margin_rate")
    var marginRate: Short? = null,
    /** 단기과열 (short_over_cls_code). 0:해당없음 1:지정예고 2:지정 3:지정연장. */
    @Column(name = "short_overheated_status", length = 1)
    var shortOverheatedStatus: String? = null,
    /** [Tier 3 분류인덱스] 지수업종 대분류 코드 (bstp_larg_div_code 4자). */
    @Column(name = "industry_large", length = 4)
    var industryLarge: String? = null,
    /** 지수업종 중분류 코드 (bstp_medm_div_code 4자). */
    @Column(name = "industry_medium", length = 4)
    var industryMedium: String? = null,
    /** 지수업종 소분류 코드 (bstp_smal_div_code 4자). */
    @Column(name = "industry_small", length = 4)
    var industrySmall: String? = null,
    /** KOSPI200 섹터 (kospi200_apnt_cls_code) — KOSPI 단독. */
    @Column(name = "kospi200_sector", length = 1)
    var kospi200Sector: String? = null,
    /** 시총규모 (avls_scal_cls_code). KOSPI: 0/1대/2중/3소, KOSDAQ: 0/1:KOSDAQ100/2:mid300/3:small. */
    @Column(name = "avls_scale", length = 1)
    var avlsScale: String? = null,
    /** KOSPI100 여부 (kospi100_issu_yn) — KOSPI 단독. */
    @Column(name = "is_kospi100", nullable = false)
    var isKospi100: Boolean = false,
    /** KOSPI50 여부 (kospi50_issu_yn) — KOSPI 단독. */
    @Column(name = "is_kospi50", nullable = false)
    var isKospi50: Boolean = false,
    /** KOSPI200 여부 (kospi200_apnt_cls_code != '0' derived) — KOSPI 단독. */
    @Column(name = "is_kospi200", nullable = false)
    var isKospi200: Boolean = false,
    /** KOSDAQ150 여부 (ksq150_nmix_yn) — KOSDAQ 단독. */
    @Column(name = "is_kosdaq150", nullable = false)
    var isKosdaq150: Boolean = false,
    /** KRX 종목 여부 (krx_issu_yn). */
    @Column(name = "is_krx", nullable = false)
    var isKrx: Boolean = false,
    /** KRX100 여부 (krx100_issu_yn). */
    @Column(name = "is_krx100", nullable = false)
    var isKrx100: Boolean = false,
    /** KRX300 여부 (krx300_issu_yn). */
    @Column(name = "is_krx300", nullable = false)
    var isKrx300: Boolean = false,
    /** KRX 자동차 (krx_car_yn). */
    @Column(name = "krx_sector_car", nullable = false)
    var krxSectorCar: Boolean = false,
    /** KRX 반도체 (krx_smcn_yn). */
    @Column(name = "krx_sector_semicon", nullable = false)
    var krxSectorSemicon: Boolean = false,
    /** KRX 바이오 (krx_bio_yn). */
    @Column(name = "krx_sector_bio", nullable = false)
    var krxSectorBio: Boolean = false,
    /** KRX 은행 (krx_bank_yn). */
    @Column(name = "krx_sector_bank", nullable = false)
    var krxSectorBank: Boolean = false,
    /** KRX 에너지화학 (krx_enrg_chms_yn). */
    @Column(name = "krx_sector_energy_chem", nullable = false)
    var krxSectorEnergyChem: Boolean = false,
    /** KRX 철강 (krx_stel_yn). */
    @Column(name = "krx_sector_steel", nullable = false)
    var krxSectorSteel: Boolean = false,
    /** KRX 미디어통신 (krx_medi_cmnc_yn). */
    @Column(name = "krx_sector_media_comm", nullable = false)
    var krxSectorMediaComm: Boolean = false,
    /** KRX 건설 (krx_cnst_yn). */
    @Column(name = "krx_sector_construction", nullable = false)
    var krxSectorConstruction: Boolean = false,
    /** KRX 증권 (krx_scrt_yn). */
    @Column(name = "krx_sector_securities", nullable = false)
    var krxSectorSecurities: Boolean = false,
    /** KRX 선박 (krx_ship_yn). */
    @Column(name = "krx_sector_ship", nullable = false)
    var krxSectorShip: Boolean = false,
    /** KRX 보험 (krx_insu_yn). */
    @Column(name = "krx_sector_insurance", nullable = false)
    var krxSectorInsurance: Boolean = false,
    /** KRX 운송 (krx_trnp_yn). */
    @Column(name = "krx_sector_transport", nullable = false)
    var krxSectorTransport: Boolean = false,
    /** 제조업 여부 (mnin_cls_code_yn) — KOSPI 단독. */
    @Column(name = "is_manufacturing", nullable = false)
    var isManufacturing: Boolean = false,
    /** 저유동성 여부 (low_current_yn). */
    @Column(name = "is_low_liquidity", nullable = false)
    var isLowLiquidity: Boolean = false,
    /** 지배구조지수 여부 (sprn_strr_nmix_issu_yn) — KOSPI 단독. */
    @Column(name = "is_governance_index", nullable = false)
    var isGovernanceIndex: Boolean = false,
    /** SPAC 여부 (etpr_undt_objt_co_yn). */
    @Column(name = "is_spac", nullable = false)
    var isSpac: Boolean = false,
    /** 벤처기업 여부 (vntr_issu_yn) — KOSDAQ 단독. */
    @Column(name = "is_venture", nullable = false)
    var isVenture: Boolean = false,
    /** SRI 지수 여부 (sri_nmix_yn) — KOSPI 단독. */
    @Column(name = "is_sri_index", nullable = false)
    var isSriIndex: Boolean = false,
    /** ETP 상품분류 (etp_prod_cls_code). 0:해당없음 1:투자회사형 2:수익증권형 3:ETN 4:손실제한ETN 5:상장형수익증권. */
    @Column(name = "etp_product_type", length = 1)
    var etpProductType: String? = null,
    /** ELW 발행 여부 (elw_pblc_yn) — KOSPI 단독. */
    @Column(name = "is_elw_publisher", nullable = false)
    var isElwPublisher: Boolean = false,
    /** 우선주 구분 (prst_cls_code). 0:보통주 1:구형우선주 2:신형우선주. */
    @Column(name = "preferred_stock_type", length = 1)
    var preferredStockType: String? = null,
    /** 그룹사 코드 (grp_code 3자리). */
    @Column(name = "group_code", length = 3)
    var groupCode: String? = null,
    /** [Tier 4 시장메타+재무] 주식 기준가 (stck_sdpr 9자리) — T-SC screening. */
    @Column(name = "base_price", precision = 15, scale = 0)
    var basePrice: BigDecimal? = null,
    /** 정규시장 매매수량단위 (frml_mrkt_deal_qty_unit 5자리) — T-O 주문 시 필요. */
    @Column(name = "trading_unit_normal")
    var tradingUnitNormal: Int? = null,
    /** 시간외 매매수량단위 (ovtm_mrkt_deal_qty_unit 5자리) — T-O 주문 시 필요. */
    @Column(name = "trading_unit_overtime")
    var tradingUnitOvertime: Int? = null,
    /** 전일 거래량 (prdy_vol 12자리) — T-IND·T-SC 거래량 필터. */
    @Column(name = "prev_volume")
    var prevVolume: Long? = null,
    /** 주식 액면가 (stck_fcam 12자리) — 분할 분석. */
    @Column(name = "face_value", precision = 15, scale = 0)
    var faceValue: BigDecimal? = null,
    /** 상장주수 천 단위 (lstn_stcn 15자리) — 시총 검증. */
    @Column(name = "listed_shares")
    var listedShares: Long? = null,
    /** 자본금 (cpfn 21자리) — 재무 분석. */
    @Column(name = "capital", precision = 25, scale = 0)
    var capital: BigDecimal? = null,
    /** 결산월 (stac_month mm). */
    @Column(name = "settlement_month")
    var settlementMonth: Short? = null,
    /** 공모가 (po_prc 7자리) — IPO 분석. */
    @Column(name = "public_offering_price", precision = 10, scale = 0)
    var publicOfferingPrice: BigDecimal? = null,
    /** 전일기준 시가총액 억 단위 (prdy_avls_scal 9자리) — T-SC 시총 필터 핵심. */
    @Column(name = "market_cap_prev")
    var marketCapPrev: Long? = null,
    /** 매출액 억 단위 (sale_account 9자리) — T-IND 재무 screening. */
    @Column(name = "sales_amount")
    var salesAmount: Long? = null,
    /** 영업이익 억 단위 (bsop_prfi 9자리). */
    @Column(name = "operating_profit")
    var operatingProfit: Long? = null,
    /** 경상이익 억 단위 (op_prfi 9자리). */
    @Column(name = "ordinary_profit")
    var ordinaryProfit: Long? = null,
    /** 당기순이익 억 단위 (thtr_ntin 5자리). */
    @Column(name = "net_income")
    var netIncome: Long? = null,
    /** ROE 자기자본이익률 (roe 9자리 부호 포함, 소수 2자리) — T-IND 핵심. */
    @Column(name = "roe", precision = 7, scale = 2)
    var roe: BigDecimal? = null,
    /** 재무 기준년월 (base_date yyyymmdd). */
    @Column(name = "financial_base_date")
    var financialBaseDate: LocalDate? = null,
    /** 회사신용한도 초과 (co_crdt_limt_over_yn) — 위험 가드 보조. */
    @Column(name = "credit_limit_exceeded", nullable = false)
    var creditLimitExceeded: Boolean = false,
    /** 담보대출 가능 (secu_lend_able_yn) — T-O 트랙. */
    @Column(name = "collateral_lendable", nullable = false)
    var collateralLendable: Boolean = false,
    /** 대주(공매도) 가능 (stln_able_yn) — T-O 공매도. */
    @Column(name = "short_lendable", nullable = false)
    var shortLendable: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}
