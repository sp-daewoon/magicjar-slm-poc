package kr.spdaewoon.magicjar.domain.backtest.port

import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult

/**
 * 백테스트 엔진 포트 (ADR 0056 §2-1 LOCK).
 *
 * 구현은 api 모듈 [kr.spdaewoon.magicjar.api.service.backtest.JvmBacktestEngine].
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 *
 * ### 의무 사항
 * - 구현체는 반드시 JvmStrategyEvaluator.evaluateBacktest() 경유 (ADR 0047 §2-3-B)
 * - 직접 walkRoot() 호출 절대 금지
 * - timeout 1000ms + abort quota 50 (ADR 0047 §2-2-A + §2-5)
 */
interface BacktestEngine {
    /**
     * 백테스트를 실행한다.
     *
     * @param request 백테스트 요청
     * @return [BacktestResult.Success] 또는 [BacktestResult.Aborted]
     */
    fun run(request: BacktestRequest): BacktestResult
}
