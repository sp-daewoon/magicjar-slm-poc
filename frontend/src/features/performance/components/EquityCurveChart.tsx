/**
 * EquityCurveChart — LWC v4 Equity Curve line chart
 *
 * ADR 0072 §3-3 Phase 3 + spec §3-3 LWC v4 chart 패턴 정합:
 * - createChart + addLineSeries (cumulativePnl 시계열)
 * - 빈 데이터 graceful: "거래 내역이 없어 Equity Curve를 표시할 수 없습니다."
 * - ResizeObserver로 container 크기 자동 추적
 * - cleanup: chart.remove()
 *
 * 룰 5: LWC import 0건 외 — anthropic·openai import 0건
 *
 * §13 LWC dispose race 가드 (ADR 0086 §2-8 amend, 2026-05-19):
 *   - chart 자체는 자식 컴포넌트로 prop 전달하지 않으므로 §13 본문 ref→state lift 미적용.
 *   - ResizeObserver async 콜백 + data update useEffect가 cleanup 후 도달할 race risk
 *     → isDisposedRef + try/catch로 disposed chart 접근 차단.
 */
import { useEffect, useRef } from 'react'
import { createChart } from 'lightweight-charts'
import type { IChartApi, ISeriesApi, LineData } from 'lightweight-charts'
import type { EquityPointDto } from '../api/performanceApi'
import { formatPerformanceChartDate, formatPerformanceCrosshairDate, toBusinessDay } from './performanceChartTime'

interface EquityCurveChartProps {
  points: EquityPointDto[] | undefined
  isLoading: boolean
  isError: boolean
}

export function EquityCurveChart({ points, isLoading, isError }: EquityCurveChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  // §13 dispose race 가드 (ADR 0086 §2-8) — async 콜백이 cleanup 이후 도달해도 차단
  const isDisposedRef = useRef(false)

  // 차트 초기화
  useEffect(() => {
    if (!containerRef.current) return
    isDisposedRef.current = false

    const chart = createChart(containerRef.current, {
      width: containerRef.current.clientWidth,
      height: 280,
      layout: {
        background: { color: 'transparent' },
        textColor: '#9ca3af',
      },
      grid: {
        vertLines: { color: '#1f2937' },
        horzLines: { color: '#1f2937' },
      },
      timeScale: {
        borderColor: '#374151',
        timeVisible: false,
        tickMarkFormatter: formatPerformanceChartDate,
      },
      rightPriceScale: {
        borderColor: '#374151',
      },
      localization: {
        locale: 'ko-KR',
        dateFormat: 'yyyy-MM-dd',
        timeFormatter: formatPerformanceCrosshairDate,
      },
      crosshair: {
        mode: 1,
      },
    })

    const lineSeries = chart.addLineSeries({
      color: '#10b981',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: true,
    })

    chartRef.current = chart
    seriesRef.current = lineSeries

    // ResizeObserver — §13 dispose race 가드 (isDisposedRef + try/catch)
    const ro = new ResizeObserver((entries) => {
      if (isDisposedRef.current) return
      const entry = entries[0]
      if (entry && chartRef.current) {
        try {
          chartRef.current.applyOptions({ width: entry.contentRect.width })
        } catch {
          // 이미 dispose된 경우 graceful (StrictMode 이중 mount race 대응)
        }
      }
    })
    ro.observe(containerRef.current)

    return () => {
      // §13 dispose 순서 LOCK: flag → series ref null → disconnect → remove(try/catch) → chart ref null
      isDisposedRef.current = true
      // series ref 먼저 null — 데이터 update useEffect의 stale ref 접근 차단
      seriesRef.current = null
      ro.disconnect()
      try {
        chart.remove()
      } catch {
        // 이미 dispose된 경우 graceful 무시
      }
      chartRef.current = null
    }
  }, [])

  // 데이터 갱신 — §13 dispose race 가드 (isDisposedRef + try/catch)
  useEffect(() => {
    if (isDisposedRef.current) return
    if (!seriesRef.current || !points) return

    try {
      if (points.length === 0) {
        seriesRef.current.setData([])
        return
      }

      const data: LineData[] = points.map((p) => ({
        time: toBusinessDay(p.tradeDate) as LineData['time'],
        value: Number(p.cumulativePnl),
      }))

      seriesRef.current.setData(data)
      chartRef.current?.timeScale().fitContent()
    } catch {
      // 이미 dispose된 경우 graceful (async data fetch 도착이 unmount 후일 때)
    }
  }, [points])

  if (isLoading) {
    return (
      <div className="flex h-[280px] items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-700 border-t-emerald-500" />
      </div>
    )
  }

  if (isError) {
    return (
      <div className="flex h-[280px] items-center justify-center rounded-lg border border-gray-800 bg-gray-900 text-sm text-red-400">
        Equity Curve 데이터를 불러오는 중 오류가 발생했습니다.
      </div>
    )
  }

  if (points !== undefined && points.length === 0) {
    return (
      <div className="flex h-[280px] flex-col items-center justify-center rounded-lg border border-gray-800 bg-gray-900">
        <p className="text-sm text-gray-500">거래 내역이 없어 Equity Curve를 표시할 수 없습니다.</p>
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-gray-800 bg-gray-900 p-2">
      <div ref={containerRef} className="w-full" />
    </div>
  )
}
