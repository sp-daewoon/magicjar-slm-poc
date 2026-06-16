package kr.spdaewoon.magicjar.domain.backtest

import kr.spdaewoon.magicjar.domain.market.candle.Candle

/**
 * 룩어헤드 가드 유틸리티 (ADR 0056 §2-2-B).
 *
 * 백테스트 시뮬레이션에서 t 시점 평가 시 t+1 이후 데이터 접근을 방지하는 slice 검증 도우미.
 *
 * ### 동작 원리
 * - [guardSlice]: maxTimestamp 이후 캔들이 포함된 경우 IllegalStateException 발생
 * - 1차 구현은 runtime check (list size 기반) — bytecode hook은 over-engineering 폐기 (ADR 0056 §3-6)
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 */
object LookaheadGuard {
    /**
     * 슬라이스가 룩어헤드를 포함하지 않는지 검증한다.
     *
     * @param slice 평가에 전달할 캔들 슬라이스
     * @param allCandles 전체 캔들 목록
     * @param currentIndex 현재 시점 인덱스 (0-based)
     * @throws IllegalStateException 슬라이스 크기가 currentIndex + 1을 초과하는 경우
     */
    fun guardSlice(
        slice: List<Candle>,
        allCandles: List<Candle>,
        currentIndex: Int,
    ) {
        val maxAllowedSize = currentIndex + 1
        check(slice.size <= maxAllowedSize) {
            "룩어헤드 감지: slice.size=${slice.size}, maxAllowed=$maxAllowedSize (currentIndex=$currentIndex). " +
                "t 시점 평가에서 t+1 이후 캔들이 노출됩니다."
        }
        check(allCandles.size >= maxAllowedSize) {
            "allCandles.size=${allCandles.size} < maxAllowedSize=$maxAllowedSize"
        }
    }

    /**
     * 슬라이스를 안전하게 생성한다 — subList(0, currentIndex + 1).
     *
     * @param allCandles 전체 캔들 목록
     * @param currentIndex 현재 시점 인덱스 (0-based, inclusive)
     * @return t 시점까지의 슬라이스 (크기 = currentIndex + 1)
     */
    fun safeSlice(
        allCandles: List<Candle>,
        currentIndex: Int,
    ): List<Candle> {
        require(currentIndex >= 0) { "currentIndex must be >= 0: $currentIndex" }
        require(currentIndex < allCandles.size) {
            "currentIndex=$currentIndex >= allCandles.size=${allCandles.size}"
        }
        return allCandles.subList(0, currentIndex + 1)
    }
}
