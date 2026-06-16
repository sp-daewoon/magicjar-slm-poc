package kr.spdaewoon.magicjar.domain.market.port

import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import java.math.BigDecimal

/**
 * KIS 멀티시세(관심종목 그룹별 시세) 조회 포트 (ADR 0118 R1 — MARKET_DAILY 야간 snapshot).
 *
 * KIS `intstock-multprice` (TR `FHKST11300006`) — **한 콜에 최대 30종목** 묶음 시세 반환.
 * 장마감 후 호출 시 그날의 완전한 일봉 1개(OHLCV + 거래대금 + 전일종가)를 종목당 1/30콜로 얻는다.
 *
 * 구현은 market-data 어댑터(consumer 모듈 `KisMultiPriceAdapter`)가 담당하고,
 * 소비(야간 snapshot 스케줄러 R2 + today 1d 봉 upsert 교정)는 batch 모듈이 한다.
 * market-data 책임 = raw 응답 변환만 (ADR 0085 영역 경계 — 캔들 적재 layer는 batch/소비측).
 *
 * KIS-first raw spec (헌법 §3 의무 인용 · 2026-06-09 raw fetch 검증):
 *   - URL path: /uapi/domestic-stock/v1/quotations/intstock-multprice
 *   - tr_id: FHKST11300006
 *   - 요청: FID_COND_MRKT_DIV_CODE_N + FID_INPUT_ISCD_N 쌍 (N = 1..30). _1 필수, _2~_30 옵션. 최대 30종목/콜.
 *   - 시장구분 입력값: "J"(KRX) 또는 "NX"(NXT)
 *   - 응답 output 필드 (verbatim · COLUMN_MAPPING 권위 인용):
 *       inter2_oprc(관심2 시가) · inter2_hgpr(관심2 고가) · inter2_lwpr(관심2 저가) ·
 *       inter2_prpr(관심2 현재가) · inter2_prdy_clpr(관심2 전일 종가) · inter2_sdpr(관심2 기준가) ·
 *       acml_vol(누적 거래량) · acml_tr_pbmn(누적 거래 대금) ·
 *       inter_shrn_iscd(관심 단축 종목코드) · kospi_kosdaq_cls_name(코스피 코스닥 구분 명) ·
 *       mrkt_trtm_cls_name(시장 조치 구분 명) · hour_cls_code(시간 구분 코드)
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/intstock_multprice.py
 *        https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/intstock_multprice/chk_intstock_multprice.py
 */
interface KisMultiPricePort {
    /**
     * 멀티시세 묶음 조회 — 종목 코드 list를 30개씩 chunk로 나눠 호출하고 결과를 합친다.
     *
     * @param codes      조회 대상 종목코드 6자리 list (내부에서 30개씩 chunk)
     * @param mrktDivCode 시장구분 — "J"(KRX) 또는 "NX"(NXT). NXT 통합 반환 여부는 R1 raw 실측 결과에 따른다.
     * @return 응답을 받은 종목만 [MultiPriceQuote]로 반환 (결손 종목은 graceful skip — 데이터 없음 path)
     */
    fun fetchMultiPrice(
        codes: List<String>,
        mrktDivCode: String = "J",
        priority: KisCallPriority = KisCallPriority.BACKFILL,
    ): List<MultiPriceQuote>
}

/**
 * 멀티시세 단일 종목 raw 응답 변환 결과 (그날 일봉 1개 매핑 source).
 *
 * 야간 snapshot(R2)이 today 1d Candle로 변환:
 *   open=open, high=high, low=low, close=close, volume=accumulatedVolume.
 * 거래대금(accumulatedTradeAmount)·전일종가(previousClose)는 검증/스크리너 보강용.
 *
 * 결손 필드는 null로 graceful 전파 (mock 합성 금지 — feedback_no_mock_data).
 */
data class MultiPriceQuote(
    /** inter_shrn_iscd — 관심 단축 종목코드 (6자리) */
    val code: String,
    /** inter2_oprc — 관심2 시가 */
    val open: BigDecimal?,
    /** inter2_hgpr — 관심2 고가 */
    val high: BigDecimal?,
    /** inter2_lwpr — 관심2 저가 */
    val low: BigDecimal?,
    /** inter2_prpr — 관심2 현재가 (마감 시점 = 종가) */
    val close: BigDecimal?,
    /** inter2_prdy_clpr — 관심2 전일 종가 */
    val previousClose: BigDecimal?,
    /** acml_vol — 누적 거래량 */
    val accumulatedVolume: Long?,
    /** acml_tr_pbmn — 누적 거래 대금 */
    val accumulatedTradeAmount: BigDecimal?,
    /** kospi_kosdaq_cls_name — 코스피 코스닥 구분 명 (NXT 실측 evidence용 raw 보존) */
    val kospiKosdaqClsName: String?,
    /** mrkt_trtm_cls_name — 시장 조치 구분 명 (정규장/NXT 시장 조치 구분 — NXT 검증 evidence) */
    val marketTreatmentClsName: String?,
    /** hour_cls_code — 시간 구분 코드 (장중/시간외/NXT 구분 가능성 — NXT 검증 evidence) */
    val hourClsCode: String?,
)
