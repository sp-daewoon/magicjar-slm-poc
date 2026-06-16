/**
 * EquityCurveChart — LWC v4 line chart, 누적 PnL 곡선 (T-BT R3)
 *
 * ADR 0025 §2-1 LOCK: LWC v4 공식 API 한정.
 * TradingView Advanced 라이브러리 import 0건.
 * 사용 API: createChart · addLineSeries · setData
 *
 * 입력: BacktestPositionResultDto[] — closeTs + pnl 기반
 * 누적 PnL = 포지션 종료 시점의 실현 손익 누적합
 *
 * feedback_no_mock_data.md 정합:
 *   positions 0건 또는 pnl 있는 포지션 0건 → "결과 데이터 없음" 안내
 *
 * 룰 5: LLM provider import 0건
 *
 * §13 LWC dispose race 가드 (ADR 0086 §2-8 amend, 2026-05-19):
 *   - chart 자체는 자식 컴포넌트로 prop 전달하지 않으므로 §13 본문 ref→state lift 미적용.
 *   - 다만 ResizeObserver async 콜백이 cleanup 후 도달할 race risk → isDisposedRef + try/catch 강화.
 *   - chart.remove() 호출 시 try/catch graceful — StrictMode 이중 mount 시에도 race 차단.
 */
import { createChart, type IChartApi, type TickMarkType, type UTCTimestamp } from 'lightweight-charts'
import { useEffect, useRef } from 'react'
import type { BacktestPositionResultDto } from '../../api/backtest'
import { formatKstAxisTick, formatKstCrosshairTime } from '../chart/chartTimeLabels'

interface EquityCurveChartProps {
  positions: BacktestPositionResultDto[]
}

/** ISO string → UTCTimestamp (초 단위) */
function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

export function EquityCurveChart({ positions }: EquityCurveChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  // §13 dispose race 가드 (ADR 0086 §2-8) — ResizeObserver async 콜백이 cleanup 이후 발화해도
  // chart.applyOptions가 disposed instance에 도달하는 것을 차단.
  const isDisposedRef = useRef(false)

  // 포지션 중 closeTs + pnl 있는 항목 필터 + 시간순 정렬
  const closedPositions = positions
    .filter((p) => p.closeTs !== null && p.pnl !== null)
    .sort((a, b) => new Date(a.closeTs!).getTime() - new Date(b.closeTs!).getTime())

  useEffect(() => {
    if (!containerRef.current) return
    isDisposedRef.current = false
    let visibleBars = Math.max(1, closedPositions.length)

    const chart = createChart(containerRef.current, {
      layout: {
        background: { color: '#0f172a' },
        textColor: '#94a3b8',
      },
      grid: {
        vertLines: { color: '#1e293b' },
        horzLines: { color: '#1e293b' },
      },
      crosshair: {
        vertLine: { color: '#475569' },
        horzLine: { color: '#475569' },
      },
      rightPriceScale: {
        borderColor: '#334155',
      },
      timeScale: {
        borderColor: '#334155',
        tickMarkFormatter: (
          time: UTCTimestamp | { year: number; month: number; day: number },
          tickMarkType: TickMarkType,
        ) => formatKstAxisTick(time, '1d', visibleBars, tickMarkType),
      },
      localization: {
        locale: 'ko-KR',
        dateFormat: 'yyyy-MM-dd',
        timeFormatter: (time: UTCTimestamp | { year: number; month: number; day: number } | string) =>
          formatKstCrosshairTime(time, '1d'),
      },
      width: containerRef.current.clientWidth,
      height: 280,
    })

    chartRef.current = chart

    const lineSeries = chart.addLineSeries({
      color: '#22d3ee',
      lineWidth: 2,
      priceFormat: {
        type: 'custom',
        formatter: (v: number) =>
          v >= 0
            ? `+${v.toLocaleString('ko-KR')}원`
            : `${v.toLocaleString('ko-KR')}원`,
      },
    })

    // 초기 자본 기준 0 포인트 추가 + 누적 PnL 계산
    if (closedPositions.length > 0) {
      let cumPnl = 0
      const points: { time: UTCTimestamp; value: number }[] = []

      // 시작점: 첫 포지션 오픈 직전 0 포인트 (오픈 ts 기준)
      const firstOpenTs = positions
        .sort((a, b) => new Date(a.openTs).getTime() - new Date(b.openTs).getTime())[0]
        ?.openTs
      if (firstOpenTs) {
        points.push({ time: toUTCTimestamp(firstOpenTs), value: 0 })
      }

      for (const pos of closedPositions) {
        cumPnl += parseFloat(pos.pnl!)
        const ts = toUTCTimestamp(pos.closeTs!)
        // 같은 ts가 이미 있으면 덮어쓰기 (LWC는 시간 중복 허용 안 함)
        const last = points[points.length - 1]
        if (last && last.time === ts) {
          last.value = Math.round(cumPnl)
        } else {
          points.push({ time: ts, value: Math.round(cumPnl) })
        }
      }

      lineSeries.setData(points)
      chart.timeScale().fitContent()
    }

    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) return
      visibleBars = Math.max(1, Math.ceil(range.to - range.from))
    })

    // ResizeObserver — §13 dispose race 가드 (isDisposedRef + try/catch)
    const ro = new ResizeObserver(() => {
      if (isDisposedRef.current) return
      if (!containerRef.current) return
      try {
        chart.applyOptions({ width: containerRef.current.clientWidth })
      } catch {
        // 이미 dispose된 경우 graceful (StrictMode 이중 mount race 대응)
      }
    })
    if (containerRef.current) ro.observe(containerRef.current)

    return () => {
      // §13 dispose 순서 LOCK: flag → disconnect → remove(try/catch) → ref null
      isDisposedRef.current = true
      ro.disconnect()
      try {
        chart.remove()
      } catch {
        // 이미 dispose된 경우 graceful 무시
      }
      chartRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [positions])

  if (closedPositions.length === 0) {
    return (
      <div className="flex h-[280px] items-center justify-center rounded-xl border border-gray-800 bg-gray-900">
        <div className="text-center">
          <p className="text-sm text-gray-500">종료 포지션 데이터가 없습니다</p>
          <p className="mt-1 text-xs text-gray-700">백테스트 실행 후 결과를 확인하세요</p>
        </div>
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-xl border border-gray-800 bg-gray-900">
      <div className="border-b border-gray-800 px-4 py-2.5">
        <h3 className="text-sm font-medium text-gray-300">누적 손익 (Equity Curve)</h3>
        <p className="mt-0.5 text-xs text-gray-600">초기 자본 기준 누적 실현 손익 (원화)</p>
      </div>
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
