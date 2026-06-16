-- V5__instrument_kis_first_expansion.sql
-- ADR 0017·0039 정합 + 사용자 비판 응답 (KIS-first 정책 위반 정정)
-- KIS open-trading-api 종목마스터정보(코스피).h + 종목마스터정보(코스닥).h + kis_kospi_code_mst.py + kis_kosdaq_code_mst.py
-- raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
-- KOSPI Part2 widths: 70 필드 sum=227byte (Python L54~L68 + .h L9~L98 정합)
-- KOSDAQ Part2 widths: 64 필드 sum=221byte (Python L54~L67 + .h L9~L88 정합)
-- 컬럼 70개 add-only ALTER. 기존 컬럼 수정 0건.

-- =============================================================================
-- Tier 1 (식별·시장 메타 보강) — 1 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS securities_group VARCHAR(2);
COMMENT ON COLUMN instrument.securities_group IS 'KIS scrt_grp_cls_code — ST/MF/RT/SC/IF/DR/EW/EF/SW/SR/BC/FE/FS (.h L9~L14)';

-- =============================================================================
-- Tier 2 (매매 가드) — 17 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_halt BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.trading_halt IS 'KIS trht_yn — 거래정지 (true 시 매매 차단)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS liquidation BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.liquidation IS 'KIS sltr_yn — 정리매매 (true 시 매수 차단)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS management_stock BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.management_stock IS 'KIS mang_issu_yn — 관리종목';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_alert VARCHAR(2);
COMMENT ON COLUMN instrument.market_alert IS 'KIS mrkt_alrm_cls_code — 시장경고 (00:없음 01:주의 02:경고 03:위험)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_alert_warning BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.market_alert_warning IS 'KIS mrkt_alrm_risk_adnt_yn — 시장경고위험 예고';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS false_disclosure BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.false_disclosure IS 'KIS insn_pbnt_yn — 불성실공시';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS backdoor_listing BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.backdoor_listing IS 'KIS byps_lstn_yn — 우회상장';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_overheated BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.short_overheated IS 'KIS ssts_hot_yn — 공매도 과열';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS abnormal_runup BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.abnormal_runup IS 'KIS stange_runup_yn — 이상급등';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS investment_caution BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.investment_caution IS 'KIS invt_alrm_yn — 투자주의환기 (KOSDAQ 단독, KOSPI는 항상 false)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS ex_rights_type VARCHAR(2);
COMMENT ON COLUMN instrument.ex_rights_type IS 'KIS flng_cls_code — 락구분 (00:없음 01:권리락 02:배당락 03:분배락 04:권배락 05:중간배당락 06:권리중간배당락 99:기타)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS face_value_change VARCHAR(2);
COMMENT ON COLUMN instrument.face_value_change IS 'KIS fcam_mod_cls_code — 액면가변경 (00:없음 01:분할 02:병합 99:기타)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS capital_increase VARCHAR(2);
COMMENT ON COLUMN instrument.capital_increase IS 'KIS icic_cls_code — 증자구분 (00:없음 01:유상 02:무상 03:유무상 99:기타)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_able BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.credit_able IS 'KIS crdt_able — 신용주문 가능';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_days SMALLINT;
COMMENT ON COLUMN instrument.credit_days IS 'KIS crdt_days — 신용기간 (일)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS margin_rate SMALLINT;
COMMENT ON COLUMN instrument.margin_rate IS 'KIS marg_rate — 증거금 비율 (%)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_overheated_status VARCHAR(1);
COMMENT ON COLUMN instrument.short_overheated_status IS 'KIS short_over_cls_code — 단기과열 (0:없음 1:지정예고 2:지정 3:지정연장)';

-- =============================================================================
-- Tier 3 (분류 인덱스) — 33 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_large VARCHAR(4);
COMMENT ON COLUMN instrument.industry_large IS 'KIS bstp_larg_div_code — 지수업종 대분류';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_medium VARCHAR(4);
COMMENT ON COLUMN instrument.industry_medium IS 'KIS bstp_medm_div_code — 지수업종 중분류';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_small VARCHAR(4);
COMMENT ON COLUMN instrument.industry_small IS 'KIS bstp_smal_div_code — 지수업종 소분류';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS kospi200_sector VARCHAR(1);
COMMENT ON COLUMN instrument.kospi200_sector IS 'KIS kospi200_apnt_cls_code — KOSPI200 섹터 (0:없음 1:건설 2:중공업 3:철강 4:에너지화학 5:정보통신 6:금융 7:필수소비재 8:자유소비재 9:산업재 A:건강관리 B:커뮤니케이션) KOSPI만';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS avls_scale VARCHAR(1);
COMMENT ON COLUMN instrument.avls_scale IS 'KIS avls_scal_cls_code — 시총규모 (KOSPI:0:제외 1:대 2:중 3:소 / KOSDAQ:0:제외 1:KOSDAQ100 2:KOSDAQmid300 3:KOSDAQsmall)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi100 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_kospi100 IS 'KIS kospi100_issu_yn — KOSPI100 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi50 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_kospi50 IS 'KIS kospi50_issu_yn — KOSPI50 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi200 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_kospi200 IS 'kospi200_apnt_cls_code != 0 에서 derived — KOSPI200 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kosdaq150 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_kosdaq150 IS 'KIS ksq150_nmix_yn — KOSDAQ150 (KOSDAQ만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_krx IS 'KIS krx_issu_yn — KRX 종목';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx100 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_krx100 IS 'KIS krx100_issu_yn — KRX100';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx300 BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_krx300 IS 'KIS krx300_issu_yn — KRX300';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_car BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_car IS 'KIS krx_car_yn — KRX 자동차';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_semicon BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_semicon IS 'KIS krx_smcn_yn — KRX 반도체';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_bio BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_bio IS 'KIS krx_bio_yn — KRX 바이오';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_bank BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_bank IS 'KIS krx_bank_yn — KRX 은행';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_energy_chem BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_energy_chem IS 'KIS krx_enrg_chms_yn — KRX 에너지화학';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_steel BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_steel IS 'KIS krx_stel_yn — KRX 철강';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_media_comm BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_media_comm IS 'KIS krx_medi_cmnc_yn — KRX 미디어통신';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_construction BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_construction IS 'KIS krx_cnst_yn — KRX 건설';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_securities BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_securities IS 'KIS krx_scrt_yn — KRX 증권';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_ship BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_ship IS 'KIS krx_ship_yn — KRX 선박';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_insurance BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_insurance IS 'KIS krx_insu_yn — KRX 보험';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_transport BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.krx_sector_transport IS 'KIS krx_trnp_yn — KRX 운송';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_manufacturing BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_manufacturing IS 'KIS mnin_cls_code_yn — 제조업 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_low_liquidity BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_low_liquidity IS 'KIS low_current_yn — 저유동성';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_governance_index BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_governance_index IS 'KIS sprn_strr_nmix_issu_yn — 지배구조지수 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_spac BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_spac IS 'KIS etpr_undt_objt_co_yn — SPAC (기업인수목적회사)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_venture BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_venture IS 'KIS vntr_issu_yn — 벤처기업 (KOSDAQ만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_sri_index BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_sri_index IS 'KIS sri_nmix_yn — SRI 지수 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS etp_product_type VARCHAR(1);
COMMENT ON COLUMN instrument.etp_product_type IS 'KIS etp_prod_cls_code — ETP 분류 (0:없음 1:투자회사형 2:수익증권형 3:ETN 4:손실제한ETN 5:상장형수익증권)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_elw_publisher BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.is_elw_publisher IS 'KIS elw_pblc_yn — ELW 발행 (KOSPI만)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS preferred_stock_type VARCHAR(1);
COMMENT ON COLUMN instrument.preferred_stock_type IS 'KIS prst_cls_code — 우선주 (0:보통주 1:구형우선주 2:신형우선주)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS group_code VARCHAR(3);
COMMENT ON COLUMN instrument.group_code IS 'KIS grp_code — 그룹사 코드';

-- =============================================================================
-- Tier 4 (시장 메타 + 재무) — 19 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS base_price NUMERIC(15,0);
COMMENT ON COLUMN instrument.base_price IS 'KIS stck_sdpr — 주식 기준가 (9자리 정수)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_unit_normal INTEGER;
COMMENT ON COLUMN instrument.trading_unit_normal IS 'KIS frml_mrkt_deal_qty_unit — 정규시장 매매수량단위 (T-O 트랙)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_unit_overtime INTEGER;
COMMENT ON COLUMN instrument.trading_unit_overtime IS 'KIS ovtm_mrkt_deal_qty_unit — 시간외 매매수량단위 (T-O 트랙)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS prev_volume BIGINT;
COMMENT ON COLUMN instrument.prev_volume IS 'KIS prdy_vol — 전일 거래량 (T-IND·T-SC 거래량 필터)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS face_value NUMERIC(15,0);
COMMENT ON COLUMN instrument.face_value IS 'KIS stck_fcam — 주식 액면가 (12자리)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS listed_shares BIGINT;
COMMENT ON COLUMN instrument.listed_shares IS 'KIS lstn_stcn — 상장주수 (15자리, 천 단위)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS capital NUMERIC(25,0);
COMMENT ON COLUMN instrument.capital IS 'KIS cpfn — 자본금 (21자리)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS settlement_month SMALLINT;
COMMENT ON COLUMN instrument.settlement_month IS 'KIS stac_month — 결산월 (mm)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS public_offering_price NUMERIC(10,0);
COMMENT ON COLUMN instrument.public_offering_price IS 'KIS po_prc — 공모가 (7자리)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_cap_prev BIGINT;
COMMENT ON COLUMN instrument.market_cap_prev IS 'KIS prdy_avls_scal — 전일기준 시가총액 (억, T-SC 핵심)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS sales_amount BIGINT;
COMMENT ON COLUMN instrument.sales_amount IS 'KIS sale_account — 매출액 (억, T-IND post-MVP)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS operating_profit BIGINT;
COMMENT ON COLUMN instrument.operating_profit IS 'KIS bsop_prfi — 영업이익 (억, T-IND post-MVP)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS ordinary_profit BIGINT;
COMMENT ON COLUMN instrument.ordinary_profit IS 'KIS op_prfi — 경상이익 (억, T-IND post-MVP)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS net_income BIGINT;
COMMENT ON COLUMN instrument.net_income IS 'KIS thtr_ntin — 당기순이익 (억, T-IND post-MVP)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS roe NUMERIC(7,2);
COMMENT ON COLUMN instrument.roe IS 'KIS roe — ROE 자기자본이익률 (음수 가능, T-IND post-MVP 핵심)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS financial_base_date DATE;
COMMENT ON COLUMN instrument.financial_base_date IS 'KIS base_date — 재무 기준년월 (yyyymmdd → DATE)';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_limit_exceeded BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.credit_limit_exceeded IS 'KIS co_crdt_limt_over_yn — 회사신용한도초과';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS collateral_lendable BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.collateral_lendable IS 'KIS secu_lend_able_yn — 담보대출 가능';
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_lendable BOOLEAN DEFAULT false;
COMMENT ON COLUMN instrument.short_lendable IS 'KIS stln_able_yn — 대주(공매도) 가능';

-- =============================================================================
-- CHECK constraint (코드 도메인 명확화) — 8건
-- =============================================================================
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_securities_group
    CHECK (securities_group IS NULL OR securities_group IN ('ST','MF','RT','SC','IF','DR','EW','EF','SW','SR','BC','FE','FS'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_market_alert
    CHECK (market_alert IS NULL OR market_alert IN ('00','01','02','03'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_ex_rights_type
    CHECK (ex_rights_type IS NULL OR ex_rights_type IN ('00','01','02','03','04','05','06','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_face_value_change
    CHECK (face_value_change IS NULL OR face_value_change IN ('00','01','02','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_capital_increase
    CHECK (capital_increase IS NULL OR capital_increase IN ('00','01','02','03','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_etp_product_type
    CHECK (etp_product_type IS NULL OR etp_product_type IN ('0','1','2','3','4','5'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_preferred_stock_type
    CHECK (preferred_stock_type IS NULL OR preferred_stock_type IN ('0','1','2'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_short_overheated_status
    CHECK (short_overheated_status IS NULL OR short_overheated_status IN ('0','1','2','3'));

-- type CHECK 보강 (기존 V1 chk_instrument_type에 'DR'·'UNKNOWN' 추가)
ALTER TABLE instrument DROP CONSTRAINT IF EXISTS chk_instrument_type;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_type
    CHECK (type IN ('STOCK','ETF','ETN','PREFERRED','WARRANT','REIT','DR','UNKNOWN'));

-- =============================================================================
-- 인덱스 add-only — Tier 1·2·3 빈출 screening 컬럼만 — 8건
-- =============================================================================
-- 매매 가드 빠른 차단용
CREATE INDEX IF NOT EXISTS idx_instrument_trading_halt ON instrument (trading_halt) WHERE trading_halt = true;
CREATE INDEX IF NOT EXISTS idx_instrument_management_stock ON instrument (management_stock) WHERE management_stock = true;
CREATE INDEX IF NOT EXISTS idx_instrument_market_alert ON instrument (market_alert) WHERE market_alert IS NOT NULL AND market_alert != '00';

-- screening universe 분류용
CREATE INDEX IF NOT EXISTS idx_instrument_securities_group ON instrument (securities_group);
CREATE INDEX IF NOT EXISTS idx_instrument_kospi200 ON instrument (is_kospi200) WHERE is_kospi200 = true;
CREATE INDEX IF NOT EXISTS idx_instrument_kosdaq150 ON instrument (is_kosdaq150) WHERE is_kosdaq150 = true;
CREATE INDEX IF NOT EXISTS idx_instrument_industry_medium ON instrument (industry_medium) WHERE industry_medium IS NOT NULL;

-- 시총 기반 screening
CREATE INDEX IF NOT EXISTS idx_instrument_market_cap_prev ON instrument (market_cap_prev DESC NULLS LAST);
