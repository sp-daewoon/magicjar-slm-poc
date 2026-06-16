package kr.spdaewoon.magicjar.domain.market.port

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 진입(BUY) marketable limit 산정용 **동기 호가 read** 포트 — ADR 0114 §10-3 (R2-C1).
 *
 * ## 신설 배경 (ADR 0114 §10-3 발굴 결함)
 * 현행 호가(H0UNASP0)는 `KisWebSocketBridge.handleOrderbookData` → Kafka `market.orderbook.v1` → STOMP로만
 * 흐르고 **동기 read 캐시가 없다**. EntryPricePolicy([EntryPriceInput])가 요구하는 `ask1`/`bid1`/`askQty1`/`quoteAsOf`를
 * 주문 시점에 읽을 곳이 부재했다. 본 포트가 그 단일 동기 read source를 제공한다.
 *
 * ## 구현(합성 어댑터) 우선순위 — ADR 0114 §10-3 ① → ②
 * 1. **1차 — Redis 호가 캐시**: H0UNASP0 WS 수신 경로(`KisWebSocketBridge`)가 write-through한 `orderbook:top:{code}`.
 *    호가 WS 미구독 종목은 **캐시 miss가 정상**이다.
 * 2. **2차 — KIS REST 호가 1회 조회**: 캐시 miss/stale 시 `FHKST01010200`
 *    (`/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn`) 1회.
 * 3. 둘 다 실패 → **null**. 시장가 fallback은 상위 정책(EntryPricePolicy)이 거부하므로 포트는 **정직하게 null** 반환한다
 *    (호가 미가용 = 진입 스킵 — ADR 0114 §3-1-5 `SIGNAL_SKIPPED_NO_QUOTE`).
 *
 * ## 계약 불변식
 * - **부수효과 0(read-only)**: 자본을 움직이지 않는다. 주문 발행은 호출자(C2 BUY 경로) 책임.
 * - **AI 호출 0**: 룰 기반 자본 흐름 데이터 read (ADR 0023 정합).
 * - 반환 [EntryQuote]는 EntryPricePolicy [EntryPriceInput]의 `ask1`/`bid1`/`askQty1`/`quoteAsOf`와 **1:1**이다.
 */
interface EntryQuotePort {
    /**
     * 종목 최신 1호가(매도1·매수1) 동기 조회. 가용 시 [EntryQuote], 미가용(캐시 miss + REST 실패) 시 null.
     *
     * @param code 종목코드(6자리 단축코드).
     * @return 최신 호가 또는 null(=호가 미가용 → 호출자가 진입 스킵).
     */
    fun latestQuote(code: String): EntryQuote?
}

/**
 * 진입 marketable limit 산정용 최소 호가 스냅샷 — EntryPricePolicy [EntryPriceInput] 입력과 1:1.
 *
 * 전체 10단계 호가([kr.spdaewoon.magicjar.domain.market.OrderbookSnapshot])가 아니라 진입 산정에 필요한
 * 1호가만 담는다(과조회 회피). 스프레드 게이트·슬리피지 캡은 ask1/bid1만으로 계산된다(EntryPricePolicy §3-1).
 *
 * @param ask1 매도1호가(양수 전제 — 구현이 ≤0/null 검증 후 미가용으로 처리).
 * @param bid1 매수1호가(스프레드 계산용).
 * @param askQty1 매도1호가 잔량(R2 thin-liquidity 게이트 입력 — ADR 0114 §9 U-2. 미가용이면 null).
 * @param asOf 호가 수신 시각(stale 판정 — EntryPricePolicy `quoteAsOf`. WS 수신 ts 또는 REST 응답 호가시각).
 */
data class EntryQuote(
    val ask1: BigDecimal,
    val bid1: BigDecimal,
    val askQty1: Long?,
    val asOf: Instant,
)
