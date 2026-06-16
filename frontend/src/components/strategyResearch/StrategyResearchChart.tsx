import { useEffect, useMemo, useRef, useState } from 'react'
import {
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type MouseEventParams,
  type SeriesMarker,
  type TickMarkType,
  type UTCTimestamp,
} from 'lightweight-charts'
import type {
  StrategyResearchCandidateDto,
  StrategyResearchCandleDto,
} from '../../api/strategyResearch'
import {
  buildAxisBoundaryTimes,
  formatKstAxisTick,
  formatKstCrosshairTime,
  normalizeChartInterval,
} from '../chart/chartTimeLabels'
import { formatMoney } from '../scenario/scenarioReplayFormat'

interface StrategyResearchChartProps {
  code: string
  interval: string
  candles: StrategyResearchCandleDto[]
  candidate: StrategyResearchCandidateDto
}

interface TooltipState {
  visible: boolean
  x: number
  y: number
  timeLabel: string
  open: number
  high: number
  low: number
  close: number
}

const TOOLTIP_WIDTH = 176
const TOOLTIP_HEIGHT = 124

function toTime(iso: string): UTCTimestamp | null {
  const value = Math.floor(new Date(iso).getTime() / 1000)
  return Number.isFinite(value) ? (value as UTCTimestamp) : null
}

function candleData(candles: StrategyResearchCandleDto[]): CandlestickData[] {
  const data: CandlestickData[] = []
  for (const candle of candles) {
    const time = toTime(candle.ts)
    if (time === null) continue
    data.push({
      time,
      open: Number(candle.open),
      high: Number(candle.high),
      low: Number(candle.low),
      close: Number(candle.close),
    })
  }
  return data
}

function markers(candidate: StrategyResearchCandidateDto): SeriesMarker<UTCTimestamp>[] {
  return candidate.trades.flatMap((trade, index) => {
    const entryTime = toTime(trade.entryTime)
    const exitTime = toTime(trade.exitTime)
    const resultColor = trade.returnPct >= 0 ? '#22c55e' : '#ef4444'
    const items: SeriesMarker<UTCTimestamp>[] = []
    if (entryTime !== null) {
      items.push({
        time: entryTime,
        position: 'belowBar',
        color: '#22c55e',
        shape: 'arrowUp',
        text: `B${index + 1}`,
      })
    }
    if (exitTime !== null) {
      items.push({
        time: exitTime,
        position: 'aboveBar',
        color: resultColor,
        shape: 'arrowDown',
        text: trade.returnPct >= 0 ? `+${trade.returnPct}%` : `${trade.returnPct}%`,
      })
    }
    return items
  }).sort((a, b) => Number(a.time) - Number(b.time))
}

export function StrategyResearchChart({ code, interval, candles, candidate }: StrategyResearchChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    x: 0,
    y: 0,
    timeLabel: '',
    open: 0,
    high: 0,
    low: 0,
    close: 0,
  })

  const data = useMemo(() => candleData(candles), [candles])
  const chartMarkers = useMemo(() => markers(candidate), [candidate])
  const chartInterval = useMemo(() => normalizeChartInterval(interval), [interval])
  const axisBoundaryTimes = useMemo(() => buildAxisBoundaryTimes(data.map((point) => point.time as UTCTimestamp)), [data])

  useEffect(() => {
    const container = containerRef.current
    if (!container || data.length === 0) return
    let visibleBars = data.length

    const chart = createChart(container, {
      width: container.clientWidth || 900,
      height: 340,
      layout: {
        background: { color: '#020617' },
        textColor: '#cbd5e1',
      },
      grid: {
        vertLines: { color: '#1e293b' },
        horzLines: { color: '#1e293b' },
      },
      rightPriceScale: {
        borderColor: '#334155',
      },
      timeScale: {
        borderColor: '#334155',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: (
          time: UTCTimestamp | { year: number; month: number; day: number },
          tickMarkType: TickMarkType,
        ) => formatKstAxisTick(
          time,
          chartInterval,
          visibleBars,
          tickMarkType,
          axisBoundaryTimes.month,
          axisBoundaryTimes.year,
          axisBoundaryTimes.day,
        ),
      },
      localization: {
        locale: 'ko-KR',
        priceFormatter: (price: number) => Math.round(price).toLocaleString('ko-KR'),
        timeFormatter: (time: UTCTimestamp | { year: number; month: number; day: number } | string) =>
          formatKstCrosshairTime(time, chartInterval),
      },
    })

    const candleSeries = chart.addCandlestickSeries({
      upColor: '#22c55e',
      downColor: '#ef4444',
      borderUpColor: '#22c55e',
      borderDownColor: '#ef4444',
      wickUpColor: '#86efac',
      wickDownColor: '#fca5a5',
    })
    candleSeries.setData(data)
    candleSeries.setMarkers(chartMarkers)

    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) return
      visibleBars = Math.max(1, Math.ceil(range.to - range.from))
    })

    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      if (!param.time || !param.point || param.point.x < 0 || param.point.y < 0) {
        setTooltip((prev) => ({ ...prev, visible: false }))
        return
      }
      const candle = param.seriesData.get(candleSeries) as CandlestickData | undefined
      if (!candle) {
        setTooltip((prev) => ({ ...prev, visible: false }))
        return
      }
      const rawX = param.point.x + 12
      const rawY = param.point.y + 12
      const x = rawX + TOOLTIP_WIDTH > container.clientWidth
        ? Math.max(8, param.point.x - TOOLTIP_WIDTH - 12)
        : rawX
      const y = rawY + TOOLTIP_HEIGHT > container.clientHeight
        ? Math.max(8, param.point.y - TOOLTIP_HEIGHT - 12)
        : rawY
      setTooltip({
        visible: true,
        x,
        y,
        timeLabel: formatKstCrosshairTime(param.time, chartInterval),
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
      })
    })

    chart.timeScale().fitContent()
    chartRef.current = chart
    candleSeriesRef.current = candleSeries

    const resize = () => {
      chart.applyOptions({ width: container.clientWidth || 900 })
    }
    window.addEventListener('resize', resize)

    return () => {
      window.removeEventListener('resize', resize)
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
    }
  }, [axisBoundaryTimes, chartInterval, chartMarkers, data])

  if (data.length === 0) {
    return (
      <div className="flex h-56 items-center justify-center rounded border border-gray-800 bg-gray-950">
        <p className="text-sm text-gray-500">표시할 candle이 없습니다</p>
      </div>
    )
  }

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs">
        <span className="text-gray-500">{code} · {interval} · candle {data.length.toLocaleString('ko-KR')}</span>
        <span className="text-gray-500">진입/청산 {candidate.trades.length.toLocaleString('ko-KR')}회</span>
      </div>
      <div className="relative h-[340px] overflow-hidden rounded border border-gray-800 bg-gray-950">
        <div ref={containerRef} className="h-full w-full" />
        {tooltip.visible && (
          <div
            className="pointer-events-none absolute z-10 rounded border border-gray-700 bg-gray-950/95 px-3 py-2 text-xs text-gray-300 shadow-xl"
            style={{ left: tooltip.x, top: tooltip.y, width: TOOLTIP_WIDTH }}
          >
            <div className="mb-1 border-b border-gray-800 pb-1 font-semibold text-gray-100">
              {tooltip.timeLabel} KST
            </div>
            <div className="grid grid-cols-2 gap-x-3 gap-y-1 tabular-nums">
              <span className="text-gray-500">시가</span>
              <span className="text-right text-gray-100">{formatMoney(tooltip.open)}</span>
              <span className="text-gray-500">고가</span>
              <span className="text-right text-emerald-300">{formatMoney(tooltip.high)}</span>
              <span className="text-gray-500">저가</span>
              <span className="text-right text-red-300">{formatMoney(tooltip.low)}</span>
              <span className="text-gray-500">종가</span>
              <span className="text-right text-gray-100">{formatMoney(tooltip.close)}</span>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
