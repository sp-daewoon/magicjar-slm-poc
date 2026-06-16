package kr.spdaewoon.magicjar.batch.infrastructure.kis

import kr.spdaewoon.magicjar.domain.instrument.InstrumentMarket
import kr.spdaewoon.magicjar.domain.instrument.InstrumentType
import java.math.BigDecimal
import java.time.LocalDate

/**
 * KIS 종목 마스터 파싱 결과 raw 전송 객체 (PR-21a market-data).
 *
 * KIS open-trading-api raw 검증 정합:
 *   raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
 *   KOSPI: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h
 *   KOSDAQ: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스닥).h
 *   Python: https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py
 *           https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py
 *
 * KOSPI: Part1(3필드 가변) + Part2(70필드 고정 227byte) = 총 73필드
 * KOSDAQ: Part1(3필드 가변) + Part2(64필드 고정 221byte) = 총 67필드
 * KOSPI/KOSDAQ 차이: KOSPI 단독 9필드 + KOSDAQ 단독 3필드 → nullable 처리
 *
 * 도메인 모델 Instrument simplicity 유지를 위해 별도 raw DTO 사용 (ADR 0017 §5-4 정합).
 * InstrumentSyncJob이 KisInstrumentRaw → BatchInstrumentJpaEntity upsert.
 */
data class KisInstrumentRaw(
    // Part1 (가변 — KOSPI/KOSDAQ 공통)
    val code: String, // mksc_shrn_iscd 9byte → 6자리 trim
    val isin: String?, // stnd_iscd 12byte
    val name: String, // hts_kor_isnm 가변 byte (cp949/EUC-KR)
    val market: InstrumentMarket,
    // Tier 1 — 식별·시장 메타 보강
    val type: InstrumentType, // scrt_grp_cls_code + prst_cls_code 권위 매핑 (mapType 참조)
    val securitiesGroup: String?, // scrt_grp_cls_code (ST/MF/RT/SC/IF/DR/EW/EF/SW/SR/BC/FE/FS)
    // Tier 2 — 매매 가드 (자동매매 본체 필수)
    val tradingHalt: Boolean, // trht_yn
    val liquidation: Boolean, // sltr_yn
    val managementStock: Boolean, // mang_issu_yn
    val marketAlert: String?, // mrkt_alrm_cls_code (00/01/02/03)
    val marketAlertWarning: Boolean, // mrkt_alrm_risk_adnt_yn
    val falseDisclosure: Boolean, // insn_pbnt_yn
    val backdoorListing: Boolean, // byps_lstn_yn
    val shortOverheated: Boolean, // ssts_hot_yn
    val abnormalRunup: Boolean, // stange_runup_yn
    val investmentCaution: Boolean, // invt_alrm_yn (KOSDAQ 단독, KOSPI = false)
    val exRightsType: String?, // flng_cls_code (00/01:권리락/02:배당락/03:분배락/04:권배락/05:중간배당락/06:권리중간배당락/99)
    val faceValueChange: String?, // fcam_mod_cls_code (00/01:분할/02:병합/99)
    val capitalIncrease: String?, // icic_cls_code (00/01:유상/02:무상/03:유무상/99)
    val creditAble: Boolean, // crdt_able
    val creditDays: Short?, // crdt_days
    val marginRate: Short?, // marg_rate (3자리 정수 %)
    val shortOverheatedStatus: String?, // short_over_cls_code (0/1:지정예고/2:지정/3:지정연장)
    // Tier 3 — 분류 인덱스 (screening·universe)
    val avlsScale: String?, // avls_scal_cls_code (KOSPI:0:제외/1대/2중/3소 KOSDAQ:0:제외/1KOSDAQ100/2KOSDAQmid300/3KOSDAQsmall)
    val industryLarge: String?, // bstp_larg_div_code
    val industryMedium: String?, // bstp_medm_div_code
    val industrySmall: String?, // bstp_smal_div_code
    val kospi200Sector: String?, // kospi200_apnt_cls_code (KOSPI만, 0:없음 1:건설 2:중공업 ... B:커뮤니케이션)
    val isKospi100: Boolean, // kospi100_issu_yn (KOSPI만)
    val isKospi50: Boolean, // kospi50_issu_yn (KOSPI만)
    val isKospi200: Boolean, // derived: kospi200_apnt_cls_code != '0' (KOSPI만)
    val isKosdaq150: Boolean, // ksq150_nmix_yn (KOSDAQ만)
    val isKrx: Boolean, // krx_issu_yn
    val isKrx100: Boolean, // krx100_issu_yn
    val isKrx300: Boolean, // krx300_issu_yn
    val krxSectorCar: Boolean, // krx_car_yn
    val krxSectorSemicon: Boolean, // krx_smcn_yn
    val krxSectorBio: Boolean, // krx_bio_yn
    val krxSectorBank: Boolean, // krx_bank_yn
    val krxSectorEnergyChem: Boolean, // krx_enrg_chms_yn
    val krxSectorSteel: Boolean, // krx_stel_yn
    val krxSectorMediaComm: Boolean, // krx_medi_cmnc_yn
    val krxSectorConstruction: Boolean, // krx_cnst_yn
    val krxSectorSecurities: Boolean, // krx_scrt_yn
    val krxSectorShip: Boolean, // krx_ship_yn
    val krxSectorInsurance: Boolean, // krx_insu_yn
    val krxSectorTransport: Boolean, // krx_trnp_yn
    val isManufacturing: Boolean, // mnin_cls_code_yn (KOSPI만)
    val isLowLiquidity: Boolean, // low_current_yn
    val isGovernanceIndex: Boolean, // sprn_strr_nmix_issu_yn (KOSPI만)
    val isSpac: Boolean, // etpr_undt_objt_co_yn
    val isVenture: Boolean, // vntr_issu_yn (KOSDAQ만)
    val isSriIndex: Boolean, // sri_nmix_yn (KOSPI만)
    val etpProductType: String?, // etp_prod_cls_code (0:없음/1:투자회사형/2:수익증권형/3:ETN/4:손실제한ETN/5:상장형수익증권)
    val isElwPublisher: Boolean, // elw_pblc_yn (KOSPI만)
    val preferredStockType: String?, // prst_cls_code (0:보통주/1:구형우선주/2:신형우선주)
    val groupCode: String?, // grp_code
    // Tier 4 — 시장 메타 + 재무
    val basePrice: BigDecimal?, // stck_sdpr (9자리)
    val tradingUnitNormal: Int?, // frml_mrkt_deal_qty_unit (5자리)
    val tradingUnitOvertime: Int?, // ovtm_mrkt_deal_qty_unit (5자리)
    val prevVolume: Long?, // prdy_vol (12자리)
    val faceValue: BigDecimal?, // stck_fcam (12자리)
    val listedAt: LocalDate?, // stck_lstn_date (yyyymmdd → LocalDate)
    val listedShares: Long?, // lstn_stcn (15자리, 천 단위)
    val capital: BigDecimal?, // cpfn (21자리)
    val settlementMonth: Short?, // stac_month (mm)
    val publicOfferingPrice: BigDecimal?, // po_prc (7자리)
    val salesAmount: Long?, // sale_account (9자리, 억)
    val operatingProfit: Long?, // bsop_prfi (9자리, 억)
    val ordinaryProfit: Long?, // op_prfi (9자리, 억)
    val netIncome: Long?, // thtr_ntin (5자리, 억)
    val roe: BigDecimal?, // roe (9자리, 음수 가능, 소수 2자리)
    val financialBaseDate: LocalDate?, // base_date (yyyymmdd → LocalDate)
    val marketCapPrev: Long?, // prdy_avls_scal (9자리, 억)
    val creditLimitExceeded: Boolean, // co_crdt_limt_over_yn
    val collateralLendable: Boolean, // secu_lend_able_yn
    val shortLendable: Boolean, // stln_able_yn
)
