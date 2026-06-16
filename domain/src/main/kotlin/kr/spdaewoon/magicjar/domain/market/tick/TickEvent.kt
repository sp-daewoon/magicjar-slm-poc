package kr.spdaewoon.magicjar.domain.market.tick

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * KIS WS H0STCNT0 체결가 tick 이벤트 도메인 모델 (ADR 0048 §3-2).
 * - PR-9b market-data가 생성 (dispatch §4-1 LOCK)
 * - producer: KisWebSocketBridge (live) / WatchlistPollingWorker (polling)
 * - consumer: TickToMinuteAggregator (Redis candle 갱신), Kafka market.tick.v1 publish
 *
 * dogfooding fix: 등락 필드 추가 (결함 1 — 싱크 불일치 해소).
 * KIS-first 출처:
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() columns 배열: [3]=PRDY_VRSS_SIGN, [4]=PRDY_VRSS, [5]=PRDY_CTRT
 * - priceChange: 전일대비 (PRDY_VRSS) — nullable: polling source는 미제공
 * - changeRate: 전일대비율 (PRDY_CTRT) — nullable: polling source는 미제공
 * - changeSign: 부호 코드 (PRDY_VRSS_SIGN: 1=상한/2=상승/3=보합/4=하한/5=하락) — nullable
 *
 * R1-A 체결강도 흡수 (ADR 0117 §3-R1 — 단타 발굴 레이어 토대, KIS 계산 완료값 흡수만 · 자체 산식 0):
 *   https://github.com/koreainvestment/open-trading-api/blob/main/examples_user/domestic_stock/domestic_stock_functions_ws.py
 *   ccnl_krx() columns 배열: [14]=ACML_TR_PBMN, [18]=CTTR, [19]=SELN_CNTG_SMTN, [20]=SHNU_CNTG_SMTN
 * - tradeIntensity: 체결강도 (CTTR — KIS 계산 완료값, 매수체결량/매도체결량×100) — nullable: polling source(WatchlistPollingWorker)·NXT 일부 미제공
 * - turnoverAccum: 장중 누적거래대금 원 (ACML_TR_PBMN) — nullable: polling source 미제공
 */
data class TickEvent(
    val code: String,
    val price: BigDecimal,
    val volume: Long,
    val ts: Instant,
    val source: String = "live",
    val stale: Boolean = false,
    /** 전일대비 (PRDY_VRSS) — KIS H0STCNT0 [4] */
    val priceChange: BigDecimal? = null,
    /** 전일대비율 % (PRDY_CTRT) — KIS H0STCNT0 [5] */
    val changeRate: BigDecimal? = null,
    /** 전일대비부호 (PRDY_VRSS_SIGN: 1=상한/2=상승/3=보합/4=하한/5=하락) — KIS H0STCNT0 [3] */
    val changeSign: String? = null,
    /** 체결강도 (CTTR — KIS 계산 완료값) — KIS H0STCNT0 [18] · polling source 미제공 시 null (ADR 0117 §3-R1) */
    val tradeIntensity: BigDecimal? = null,
    /** 장중 누적거래대금 원 (ACML_TR_PBMN) — KIS H0STCNT0 [14] · polling source 미제공 시 null (ADR 0117 §3-R1) */
    val turnoverAccum: BigDecimal? = null,
)
