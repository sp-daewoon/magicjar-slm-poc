package kr.spdaewoon.magicjar.strategy.entry

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * KRX 국내주식 호가단위(tick size) 순수 함수 — ADR 0114 §6 "호가단위 테이블 필요 시 순수 함수로 내장".
 *
 * 2023-01-25 KRX 호가가격단위 개편(7단계) 기준. KOSPI·KOSDAQ 주식·ETN 공통 적용.
 * (ETF/ELW 등 일부 종목군은 별도 단위가 있으나, magicJar 자동매매 대상은 일반 주식이므로 주식 단위를 적용한다.)
 *
 * | 가격(원) | 호가단위(원) |
 * |---|---|
 * | ~ 1,999 | 1 |
 * | 2,000 ~ 4,999 | 5 |
 * | 5,000 ~ 19,999 | 10 |
 * | 20,000 ~ 49,999 | 50 |
 * | 50,000 ~ 199,999 | 100 |
 * | 200,000 ~ 499,999 | 500 |
 * | 500,000 ~ | 1,000 |
 *
 * ## 순수성 (LOCK)
 * Spring·JPA·Clock·IO 의존 0. 가격(BigDecimal) → 호가단위(BigDecimal) 결정론. 동일 입력 → 동일 출력.
 *
 * ## marketable BUY 라운딩 방향 (자본 보호)
 * 진입 상한(cap)을 호가단위로 내릴 때는 **반드시 내림(FLOOR)** 한다 —
 * 올림하면 산정 limitPrice가 슬리피지 cap을 초과해 자기 부정([EntryPricePolicy] §캡 정합).
 */
object KrxTickSize {
    private val BANDS: List<Pair<BigDecimal, BigDecimal>> =
        listOf(
            BigDecimal("2000") to BigDecimal("1"),
            BigDecimal("5000") to BigDecimal("5"),
            BigDecimal("20000") to BigDecimal("10"),
            BigDecimal("50000") to BigDecimal("50"),
            BigDecimal("200000") to BigDecimal("100"),
            BigDecimal("500000") to BigDecimal("500"),
        )

    /** 최고 구간(500,000원 이상) 호가단위. */
    private val TOP_TICK: BigDecimal = BigDecimal("1000")

    /**
     * 가격이 속한 KRX 호가단위를 반환한다. price는 양수 전제(호출부 가드).
     *
     * 구간 경계는 **미만(exclusive upper)** — 예: 2,000원 정확히는 5원 단위 구간(>= 2,000).
     */
    fun tickSizeFor(price: BigDecimal): BigDecimal {
        for ((upperExclusive, tick) in BANDS) {
            if (price < upperExclusive) return tick
        }
        return TOP_TICK
    }

    /**
     * 가격을 호가단위로 내림 정렬한다(marketable BUY 상한 정렬 — 자본 보호).
     *
     * 호가단위는 **floor된 결과 가격이 속한 구간**으로 재확정한다 — 구간 경계 직하 가격이
     * 상위 구간 단위로 잘못 내려가는 오차를 막는다(예: 50,000 직하 49,990을 100단위로 내리면 49,900으로
     * 과도 하향 → 실제 49,990은 50원 단위 구간이므로 49,950이 맞다).
     */
    fun floorToTick(price: BigDecimal): BigDecimal {
        require(price >= BigDecimal.ZERO) { "price must be >= 0: $price" }
        if (price.signum() == 0) return BigDecimal.ZERO
        // 1차: 입력 가격 구간 단위로 내림
        var tick = tickSizeFor(price)
        var floored = floorBy(price, tick)
        // 2차: 내림 결과가 더 낮은 구간으로 떨어졌으면 그 구간 단위로 재정렬(경계 직하 보정)
        val reTick = tickSizeFor(floored)
        if (reTick < tick) {
            tick = reTick
            floored = floorBy(price, tick)
        }
        return floored
    }

    private fun floorBy(
        price: BigDecimal,
        tick: BigDecimal,
    ): BigDecimal =
        price
            .divide(tick, 0, RoundingMode.FLOOR)
            .multiply(tick)
}
