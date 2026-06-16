package kr.spdaewoon.magicjar.api.controller

/**
 * 종목 검색 응답 DTO (GET /api/instruments/search).
 * dispatch-spec §5-3 LOCK 정합.
 */
data class InstrumentSearchResponseDto(
    val query: String,
    val results: List<InstrumentSearchItemDto>,
    val totalMatched: Int,
    val limit: Int,
)

/**
 * 종목 검색 결과 단건 DTO.
 * score: 검색 일치도 (1.0 = 완전 일치).
 * PR-21b 보강: type·marketCapPrev·tradingHalt 추가 (검색 결과에 위험 가드 표시).
 */
data class InstrumentSearchItemDto(
    val code: String,
    val name: String,
    val market: String,
    val sector: String?,
    val score: Double,
    // PR-21b 신규 — 검색 결과 위험 가드 노출 (graceful: V5 sync 전 null/false)
    val type: String? = null,
    val marketCapPrev: Long? = null,
    val tradingHalt: Boolean = false,
)

/**
 * 종목 상세 응답 DTO (GET /api/instruments/{code}).
 * dispatch-spec §5-7 예시 페이로드 정합.
 *
 * PR-21b 보강: KIS-first 70 필드에서 Tier 1·2·3 핵심 필드 응답 schema 추가.
 * architect 결정: Tier 1·2·3 핵심 필드만 노출 (Tier 4 재무는 T-IND 후속).
 * 사용자 친화적 그룹화: tradingGuards·classification·marketMeta.
 */
data class InstrumentDetailDto(
    val code: String,
    val isin: String?,
    val name: String,
    val nameEn: String?,
    val market: String,
    val type: String,
    val sector: String?,
    val theme: String?,
    val listedAt: String?,
    val delistedAt: String?,
    val active: Boolean,
    val aliases: List<InstrumentAliasDto>,
    // PR-21b 신규 — Tier 1 보강
    val securitiesGroup: String? = null,
    // PR-21b 신규 — Tier 2 매매 가드 그룹 (tradingGuards)
    val tradingGuards: InstrumentTradingGuardsDto? = null,
    // PR-21b 신규 — Tier 3 분류 인덱스 그룹 (classification)
    val classification: InstrumentClassificationDto? = null,
    // PR-21b 신규 — Tier 4 시장 메타 일부 그룹 (marketMeta — 재무 제외)
    val marketMeta: InstrumentMarketMetaDto? = null,
)

/**
 * 매매 가드 그룹 (Tier 2) — 자동매매 본체 주문 안전망.
 * V5 sync 전: 모든 Boolean false, 문자열 null (graceful).
 */
data class InstrumentTradingGuardsDto(
    /** 거래정지 여부 (trht_yn). */
    val tradingHalt: Boolean = false,
    /** 정리매매 여부 (sltr_yn). */
    val liquidation: Boolean = false,
    /** 관리종목 여부 (mang_issu_yn). */
    val managementStock: Boolean = false,
    /** 시장경고 코드 (00:정상 01:투자주의 02:투자경고 03:투자위험). */
    val marketAlert: String? = null,
    /** 공매도 과열 (ssts_hot_yn). */
    val shortOverheated: Boolean = false,
    /** 이상급등 (stange_runup_yn). */
    val abnormalRunup: Boolean = false,
    /** 불성실공시 (insn_pbnt_yn). */
    val falseDisclosure: Boolean = false,
    /** 우회상장 (byps_lstn_yn). */
    val backdoorListing: Boolean = false,
    /** 신용주문 가능 (crdt_able). */
    val creditAble: Boolean = false,
    /** 증거금 비율 % (marg_rate). */
    val marginRate: Short? = null,
    /** 단기과열 (short_over_cls_code). 0:정상 1:예고 2:지정 3:연장. */
    val shortOverheatedStatus: String? = null,
)

/**
 * 분류 인덱스 그룹 (Tier 3) — screening·universe 분류.
 */
data class InstrumentClassificationDto(
    /** 지수업종 대분류 (bstp_larg_div_code). */
    val industryLarge: String? = null,
    /** 지수업종 중분류 (bstp_medm_div_code). */
    val industryMedium: String? = null,
    /** 지수업종 소분류 (bstp_smal_div_code). */
    val industrySmall: String? = null,
    /** KOSPI200 편입 여부. */
    val isKospi200: Boolean = false,
    /** KOSPI200 섹터 (kospi200_apnt_cls_code). */
    val kospi200Sector: String? = null,
    /** KOSDAQ150 편입 여부. */
    val isKosdaq150: Boolean = false,
    /** KOSPI100 편입 여부. */
    val isKospi100: Boolean = false,
    /** KOSPI50 편입 여부. */
    val isKospi50: Boolean = false,
    /** KRX 종목 여부. */
    val isKrx: Boolean = false,
    /** KRX100 여부. */
    val isKrx100: Boolean = false,
    /** KRX300 여부. */
    val isKrx300: Boolean = false,
    /** SPAC 여부. */
    val isSpac: Boolean = false,
    /** 벤처기업 여부 (KOSDAQ). */
    val isVenture: Boolean = false,
    /** 저유동성 여부. */
    val isLowLiquidity: Boolean = false,
    /** 우선주 구분 (0:보통주 1:구형 2:신형). */
    val preferredStockType: String? = null,
    /** ETP 상품분류 코드. */
    val etpProductType: String? = null,
    /** 시총규모 코드 (avls_scal_cls_code). */
    val avlsScale: String? = null,
)

/**
 * 시장 메타 그룹 (Tier 4 일부 — 기준가·매매단위·거래량·상장정보).
 * 재무 (ROE·매출액 등)는 T-IND 후속 사이클에서 노출.
 */
data class InstrumentMarketMetaDto(
    /** 주식 기준가 (stck_sdpr). */
    val basePrice: String? = null,
    /** 정규시장 매매수량단위 (frml_mrkt_deal_qty_unit). */
    val tradingUnitNormal: Int? = null,
    /** 전일 거래량 (prdy_vol). */
    val prevVolume: Long? = null,
    /** 전일기준 시가총액 억 단위 (prdy_avls_scal). */
    val marketCapPrev: Long? = null,
    /** 상장주수 천 단위 (lstn_stcn). */
    val listedShares: Long? = null,
    /** 결산월 (stac_month). */
    val settlementMonth: Short? = null,
)

/**
 * 종목 alias DTO.
 */
data class InstrumentAliasDto(
    val alias: String,
    val source: String,
)

/**
 * 종목 universe 단건 DTO (GET /api/instruments).
 */
data class InstrumentUniverseItemDto(
    val code: String,
    val name: String,
    val market: String,
    val type: String,
    val sector: String?,
    val sectorKey: String,
    val sectorName: String,
    val industryMedium: String? = null,
    val marketCapPrev: Long? = null,
    val tradingHalt: Boolean = false,
    val managementStock: Boolean = false,
    val active: Boolean,
)

/**
 * 종목 universe 응답 DTO.
 */
data class InstrumentUniverseResponseDto(
    val items: List<InstrumentUniverseItemDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * 수동 sync trigger 응답 DTO.
 */
data class InstrumentSyncTriggerResponse(
    val triggered: Boolean,
    val activeInstruments: Int,
)
