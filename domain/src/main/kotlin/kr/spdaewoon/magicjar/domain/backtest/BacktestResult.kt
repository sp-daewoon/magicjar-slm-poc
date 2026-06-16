package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.Instant
import java.math.BigDecimal

/**
 * 백테스트 실행 결과 (ADR 0056 §2-1 LOCK).
 *
 * sealed class — Success · Aborted 두 상태만 허용.
 * 도메인 모듈에만 위치 — Spring·JPA·Kafka import 절대 금지.
 */
sealed class BacktestResult {
    /**
     * 백테스트 정상 완료.
     *
     * @param request 원본 요청
     * @param trades 전체 매매 기록
     * @param positions 전체 포지션 기록 (종료 + 미종료)
     * @param finalCapital 최종 자본 (KRW)
     * @param performance 성과 지표
     * @param executedAt 실행 완료 시각
     * @param durationMs 소요 시간 (ms)
     * @param entrySkip 진입 정책(EntryPricePolicy) 적용으로 스킵된 BUY 기회 집계 (ADR 0114 R3). 진입 정책 미설정 전략은 0.
     */
    data class Success(
        val request: BacktestRequest,
        val trades: List<BacktestTrade>,
        val positions: List<BacktestPosition>,
        val finalCapital: BigDecimal,
        val performance: BacktestPerformance,
        val executedAt: Instant,
        val durationMs: Long,
        val entrySkip: BacktestEntrySkip = BacktestEntrySkip.NONE,
    ) : BacktestResult()

    /**
     * 백테스트 중단 — timeout 누적 50회 초과 또는 평가 오류 (ADR 0047 §2-5 + ADR 0056 §2-5).
     *
     * 부분 매매 기록(partialTrades)은 보존 — 사용자에게 중단 시점까지 결과 제공.
     *
     * @param request 원본 요청
     * @param partialTrades 중단 시점까지의 매매 기록
     * @param reason 중단 사유 ("TIMEOUT_QUOTA_EXCEEDED" · "EVALUATION_ERROR" · "NO_CANDLE_DATA")
     * @param timeoutCount 누적 timeout 횟수
     * @param executedAt 중단 시각
     * @param durationMs 중단까지 소요 시간 (ms)
     */
    data class Aborted(
        val request: BacktestRequest,
        val partialTrades: List<BacktestTrade>,
        val reason: String,
        val timeoutCount: Int,
        val executedAt: Instant,
        val durationMs: Long,
    ) : BacktestResult()
}

/**
 * 백테스트 진입 정책 스킵 집계 — ADR 0114 R3 (백테스트 진입 정책 정합).
 *
 * 전략에 [kr.spdaewoon.magicjar.domain.strategy.EntryPolicy]가 있으면 백테스트 진입도 live와 **동일한**
 * [kr.spdaewoon.magicjar.strategy.entry.EntryPricePolicy.evaluate] 순수 함수로 판정한다. 스킵된 BUY 기회는
 * 그 시점의 진입 기회가 소멸한 것으로 보고 사유별로 집계한다(add-only — 기존 결과 구조 0 변경).
 *
 * ## 백테스트 근사 한계 (정직 기록 — ADR 0114 R3)
 * 백테스트에는 호가(ask1/bid1) 시계열이 없다. 따라서:
 * - **슬리피지 캡 게이트만** 의미 있게 평가한다(ask1 ≈ 슬리피지 적용 체결가, priceAtSignal = 봉 평가가).
 * - **스프레드 게이트는 평가 불가** — 호가 부재로 ask1≈bid1로 근사(spread 0)해 게이트가 사실상 no-op.
 * - **stale 게이트는 평가 불가** — quoteAsOf=evalAt로 근사(항상 fresh).
 * 즉 백테스트는 진입 정책 중 캡 판정만 재현하며, 스프레드/stale는 live에서만 발화한다(near-fill 가정).
 *
 * @param noQuote 호가 미가용 스킵 — 백테스트에선 0 (ask1≈체결가 항상 주입).
 * @param quoteStale 호가 stale 스킵 — 백테스트에선 0 (quoteAsOf=evalAt 근사).
 * @param spreadTooWide 스프레드 게이트 스킵 — 백테스트에선 0 (호가 부재로 평가 불가).
 * @param priceCapExceeded 슬리피지 캡 초과 스킵 — 백테스트에서 유일하게 발화 가능한 사유.
 */
data class BacktestEntrySkip(
    val noQuote: Int = 0,
    val quoteStale: Int = 0,
    val spreadTooWide: Int = 0,
    val priceCapExceeded: Int = 0,
) {
    /** 총 스킵 횟수. */
    val total: Int get() = noQuote + quoteStale + spreadTooWide + priceCapExceeded

    companion object {
        val NONE = BacktestEntrySkip()
    }
}
