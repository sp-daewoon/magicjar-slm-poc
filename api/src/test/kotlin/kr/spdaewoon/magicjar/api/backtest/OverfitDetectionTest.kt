package kr.spdaewoon.magicjar.api.backtest

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kr.spdaewoon.magicjar.api.service.backtest.OverfitDetector
import kr.spdaewoon.magicjar.api.service.backtest.SensitivityAnalyzer
import kr.spdaewoon.magicjar.domain.backtest.BacktestException
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.ExitParam
import kr.spdaewoon.magicjar.domain.backtest.SensitivityPoint
import kr.spdaewoon.magicjar.domain.backtest.SensitivityRequest
import kr.spdaewoon.magicjar.domain.backtest.port.BacktestEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal

/**
 * 과최적화 탐지 단위 테스트 (ADR 0117 §2-6 · spec §3-R3).
 *
 * IS/OOS 분할 동작 · degradation 계산 · 판정 LOCK · 민감도 그리드 sweep · plateau 판정 검증.
 * 엔진은 mock — orchestration(2-run·그리드 루프) 자체와 순수 산식만 검증(ADR 0056 코어 무변경).
 */
class OverfitDetectionTest {
    private val engine: BacktestEngine = mock()
    private val overfitDetector = OverfitDetector(engine)
    private val sensitivityAnalyzer = SensitivityAnalyzer(engine)

    private fun perf(
        totalReturn: String,
        maxDrawdown: String = "0.05",
        totalTrades: Int = 120,
        sharpe: String? = null,
    ): BacktestPerformance =
        BacktestPerformance(
            totalReturn = BigDecimal(totalReturn),
            cagr = BigDecimal(totalReturn),
            maxDrawdown = BigDecimal(maxDrawdown),
            sharpeRatio = sharpe?.let { BigDecimal(it) },
            winRate = BigDecimal("0.55"),
            totalTrades = totalTrades,
            winningTrades = totalTrades / 2,
            losingTrades = totalTrades / 2,
            avgWin = BigDecimal("1000"),
            avgLoss = BigDecimal("800"),
            profitFactor = BigDecimal("1.3"),
            tradingFrequency = BigDecimal("0.5"),
        )

    private fun success(performance: BacktestPerformance): BacktestResult.Success =
        BacktestResult.Success(
            request = baseRequest(),
            trades = emptyList(),
            positions = emptyList(),
            finalCapital = BigDecimal("10000000"),
            performance = performance,
            executedAt = Clock.System.now(),
            durationMs = 1L,
        )

    private fun baseRequest(
        split: LocalDate? = null,
        sizingPolicy: kr.spdaewoon.magicjar.domain.backtest.SizingPolicy = kr.spdaewoon.magicjar.domain.backtest.SizingPolicy.AllCapital,
    ): BacktestRequest =
        BacktestRequest(
            strategyId = 1L,
            universe = listOf("005930"),
            interval = "1d",
            from = LocalDate(2023, 1, 1),
            to = LocalDate(2025, 1, 1),
            initialCapital = BigDecimal("10000000"),
            sizingPolicy = sizingPolicy,
            outOfSampleSplit = split,
        )

    // ── IS/OOS 분할 ───────────────────────────────────────────────────────────

    @Test
    fun `검증1_IS OOS 2-run 분할 — 엔진 두 번 호출 + 기간 경계 분할`() {
        // IS 우수(20%) · OOS 양호(12%) → passed
        whenever(engine.run(any()))
            .doReturn(success(perf("0.20")))
            .doReturn(success(perf("0.12")))

        val result = overfitDetector.detect(baseRequest(split = LocalDate(2024, 6, 1)))

        val captor = argumentCaptor<BacktestRequest>()
        verify(engine, org.mockito.kotlin.times(2)).run(captor.capture())
        val isReq = captor.firstValue
        val oosReq = captor.secondValue

        // IS = [from, split-1] · OOS = [split, to]
        assertEquals(LocalDate(2023, 1, 1), isReq.from)
        assertEquals(LocalDate(2024, 5, 31), isReq.to)
        assertEquals(LocalDate(2024, 6, 1), oosReq.from)
        assertEquals(LocalDate(2025, 1, 1), oosReq.to)
        // 각 sub-run은 단일 run(split null)으로 호출돼야 무한 재귀가 없다.
        assertNull(isReq.outOfSampleSplit)
        assertNull(oosReq.outOfSampleSplit)

        assertTrue(result.passed, "OOS 12% + 거래120 + MDD5% → 통과")
    }

    @Test
    fun `검증1b_sizingPolicy가 IS OOS 두 run에 copy로 자동 전파 (ADR 0121 §2-3)`() {
        whenever(engine.run(any()))
            .doReturn(success(perf("0.20")))
            .doReturn(success(perf("0.12")))

        val fractional =
            kr.spdaewoon.magicjar.domain.backtest.SizingPolicy.FixedFractional(
                positionPercent = BigDecimal("10.00"),
                maxConcurrent = 5,
            )
        overfitDetector.detect(baseRequest(split = LocalDate(2024, 6, 1), sizingPolicy = fractional))

        val captor = argumentCaptor<BacktestRequest>()
        verify(engine, org.mockito.kotlin.times(2)).run(captor.capture())
        // IS·OOS 두 run 모두 base의 FixedFractional을 보존(copy가 sizingPolicy를 reset하지 않음).
        assertEquals(fractional, captor.firstValue.sizingPolicy, "IS run에 사이징 전파")
        assertEquals(fractional, captor.secondValue.sizingPolicy, "OOS run에 사이징 전파")
    }

    @Test
    fun `검증2_split 미지정 → InvalidRequestException`() {
        assertThrows(BacktestException.InvalidRequestException::class.java) {
            overfitDetector.detect(baseRequest(split = null))
        }
    }

    @Test
    fun `검증3_OOS 구간 Aborted → InsufficientCandleDataException`() {
        whenever(engine.run(any()))
            .doReturn(success(perf("0.20")))
            .doReturn(
                BacktestResult.Aborted(
                    request = baseRequest(),
                    partialTrades = emptyList(),
                    reason = "NO_CANDLE_DATA",
                    timeoutCount = 0,
                    executedAt = Clock.System.now(),
                    durationMs = 1L,
                ),
            )
        assertThrows(BacktestException.InsufficientCandleDataException::class.java) {
            overfitDetector.detect(baseRequest(split = LocalDate(2024, 6, 1)))
        }
    }

    // ── degradation 계산 ───────────────────────────────────────────────────────

    @Test
    fun `검증4_degradation = (IS - OOS) over IS`() {
        // IS 20% · OOS 12% → degradation = (0.20 - 0.12)/0.20 = 0.4
        val d = overfitDetector.diagnose(perf("0.20"), perf("0.12"))
        assertEquals(0, BigDecimal("0.4").compareTo(d.returnDegradation!!))
    }

    @Test
    fun `검증5_IS 수익 0 이하면 degradation null + 사유`() {
        val d = overfitDetector.diagnose(perf("-0.05"), perf("0.10"))
        assertNull(d.returnDegradation)
        assertEquals("IS_RETURN_NON_POSITIVE", d.degradationUndefinedReason)
    }

    @Test
    fun `검증6_sharpe 둘 다 있으면 차, 하나라도 null이면 null`() {
        val both = overfitDetector.diagnose(perf("0.20", sharpe = "1.5"), perf("0.12", sharpe = "0.9"))
        assertEquals(0, BigDecimal("0.6").compareTo(both.sharpeDegradation!!))

        val oneNull = overfitDetector.diagnose(perf("0.20", sharpe = "1.5"), perf("0.12", sharpe = null))
        assertNull(oneNull.sharpeDegradation)
    }

    // ── 판정 LOCK (OOS 기대값>0 && 거래≥100 && MDD≤8%) ───────────────────────────

    @Test
    fun `검증7_판정 통과 — 기대값+ 거래100 MDD5`() {
        val d = overfitDetector.diagnose(perf("0.20"), perf("0.10", maxDrawdown = "0.05", totalTrades = 120))
        assertTrue(d.passed)
        assertTrue(d.oosExpectancyPositive)
        assertTrue(d.oosTradesSufficient)
        assertTrue(d.oosDrawdownControlled)
    }

    @Test
    fun `검증8_OOS 기대값 음수 → 과최적화 의심 fail`() {
        val d = overfitDetector.diagnose(perf("0.20"), perf("-0.03", maxDrawdown = "0.05", totalTrades = 120))
        assertFalse(d.passed)
        assertFalse(d.oosExpectancyPositive)
    }

    @Test
    fun `검증9_OOS 거래수 부족 100미만 → fail`() {
        val d = overfitDetector.diagnose(perf("0.20"), perf("0.10", totalTrades = 50))
        assertFalse(d.passed)
        assertFalse(d.oosTradesSufficient)
    }

    @Test
    fun `검증10_OOS MDD 8퍼센트 초과 → fail`() {
        val d = overfitDetector.diagnose(perf("0.20"), perf("0.10", maxDrawdown = "0.12", totalTrades = 120))
        assertFalse(d.passed)
        assertFalse(d.oosDrawdownControlled)
    }

    // ── 민감도 그리드 sweep ───────────────────────────────────────────────────

    @Test
    fun `검증11_그리드 sweep — 점 수만큼 run + exitParamOverride 주입`() {
        whenever(engine.run(any())).doReturn(success(perf("0.10")))

        val req =
            SensitivityRequest(
                base = baseRequest(),
                paramName = ExitParam.FIXED_STOP_LOSS.paramName,
                grid = listOf(BigDecimal("2"), BigDecimal("3"), BigDecimal("4")),
            )
        sensitivityAnalyzer.analyze(req)

        val captor = argumentCaptor<BacktestRequest>()
        verify(engine, org.mockito.kotlin.times(3)).run(captor.capture())
        // 각 run에 grid 값이 fixedStopLoss override로 주입되고, split은 null이어야 한다.
        assertEquals(listOf(BigDecimal("2"), BigDecimal("3"), BigDecimal("4")), captor.allValues.map { it.exitParamOverride!!.value })
        assertTrue(captor.allValues.all { it.exitParamOverride!!.target == ExitParam.FIXED_STOP_LOSS })
        assertTrue(captor.allValues.all { it.outOfSampleSplit == null })
    }

    @Test
    fun `검증12_plateau — 인접 점 변동 작으면 robust true`() {
        // 10% · 11% · 10.5% · 11% → 변동 작음 → 고원
        val points =
            listOf(
                SensitivityPoint(BigDecimal("2"), perf("0.10")),
                SensitivityPoint(BigDecimal("3"), perf("0.11")),
                SensitivityPoint(BigDecimal("4"), perf("0.105")),
                SensitivityPoint(BigDecimal("5"), perf("0.11")),
            )
        val r = sensitivityAnalyzer.summarize(points, ExitParam.FIXED_STOP_LOSS.paramName)
        assertTrue(r.isPlateau, "인접 변동 작음 → 고원")
        assertEquals(0, BigDecimal("3").compareTo(r.bestParamValue))
    }

    @Test
    fun `검증13_절벽 — 한 점만 급등하면 과최적화 의심 false`() {
        // 1% · 1% · 30% · 1% → 한 점만 절벽처럼 솟음 → 비고원
        val points =
            listOf(
                SensitivityPoint(BigDecimal("2"), perf("0.01")),
                SensitivityPoint(BigDecimal("3"), perf("0.01")),
                SensitivityPoint(BigDecimal("4"), perf("0.30")),
                SensitivityPoint(BigDecimal("5"), perf("0.01")),
            )
        val r = sensitivityAnalyzer.summarize(points, ExitParam.TAKE_PROFIT.paramName)
        assertFalse(r.isPlateau, "한 점 급등(절벽) → 과최적화 의심")
        assertEquals(0, BigDecimal("4").compareTo(r.bestParamValue))
    }

    @Test
    fun `검증14_Aborted 점은 performance null + 유효점만 plateau`() {
        whenever(engine.run(any()))
            .doReturn(success(perf("0.10")))
            .doReturn(
                BacktestResult.Aborted(
                    request = baseRequest(),
                    partialTrades = emptyList(),
                    reason = "NO_CANDLE_DATA",
                    timeoutCount = 0,
                    executedAt = Clock.System.now(),
                    durationMs = 1L,
                ),
            ).doReturn(success(perf("0.11")))

        val req =
            SensitivityRequest(
                base = baseRequest(),
                paramName = ExitParam.TRAILING_STOP.paramName,
                grid = listOf(BigDecimal("1"), BigDecimal("2"), BigDecimal("3")),
            )
        val r = sensitivityAnalyzer.analyze(req)
        assertEquals(3, r.points.size)
        assertNull(r.points[1].performance)
        assertEquals("NO_CANDLE_DATA", r.points[1].skippedReason)
    }

    @Test
    fun `검증15_알 수 없는 paramName → init 예외`() {
        assertThrows(IllegalArgumentException::class.java) {
            SensitivityRequest(base = baseRequest(), paramName = "unknown_param", grid = listOf(BigDecimal("1"), BigDecimal("2")))
        }
    }
}
