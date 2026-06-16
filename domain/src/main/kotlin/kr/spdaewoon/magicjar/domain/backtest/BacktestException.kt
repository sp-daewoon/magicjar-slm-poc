package kr.spdaewoon.magicjar.domain.backtest

/**
 * 백테스트 도메인 예외 (ADR 0056 §2-1).
 *
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 */
sealed class BacktestException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /**
     * 캔들 데이터 부족 — backfill 미완료 또는 universe 조회 결과 0건.
     * HTTP 503 매핑 의무 (spdaewoon feedback_no_mock_data.md 정합).
     */
    class InsufficientCandleDataException(
        code: String,
        interval: String,
        from: String,
        to: String,
    ) : BacktestException(
            "캔들 데이터 부족: code=$code, interval=$interval, from=$from, to=$to — backfill 미완료.",
        )

    /**
     * 전략 미존재 — strategyId 조회 실패.
     * HTTP 400 매핑 의무.
     */
    class StrategyNotFoundException(
        strategyId: Long,
    ) : BacktestException("전략을 찾을 수 없습니다: strategyId=$strategyId")

    /**
     * 전략 DSL 파싱 실패 — 저장된 DSL이 유효하지 않음.
     * HTTP 400 매핑 의무.
     */
    class InvalidStrategyDslException(
        strategyId: Long,
        detail: String,
    ) : BacktestException("전략 DSL 파싱 실패: strategyId=$strategyId, detail=$detail")

    /**
     * 잘못된 요청 파라미터 — from > to, universe 빈 목록, 초기 자본 0 이하 등.
     * HTTP 400 매핑 의무.
     */
    class InvalidRequestException(
        detail: String,
    ) : BacktestException("잘못된 백테스트 요청: $detail")
}
