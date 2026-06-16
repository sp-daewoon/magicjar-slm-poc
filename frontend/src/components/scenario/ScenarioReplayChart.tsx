import { useEffect, useMemo, useRef, useState } from 'react'
import {
  createChart,
  type CandlestickData,
  type IChartApi,
  type ISeriesApi,
  type LineData,
  type MouseEventParams,
  type SeriesMarker,
  type TickMarkType,
  type UTCTimestamp,
} from 'lightweight-charts'
import type { ScenarioEventDto, ScenarioRunSummaryDto } from '../../api/scenarioReplay'
import {
  buildAxisBoundaryTimes,
  formatKstAxisTick,
  formatKstCrosshairTime,
  normalizeChartInterval,
} from '../chart/chartTimeLabels'
import { eventLabel, formatKst, formatMoney } from './scenarioReplayFormat'

interface ScenarioReplayChartProps {
  run: ScenarioRunSummaryDto
  events: ScenarioEventDto[]
  showDiagnosticEvents: boolean
}

interface ReplayCandlePoint {
  time: UTCTimestamp
  iso: string
  open: number
  high: number
  low: number
  close: number
}

interface EventSummary {
  candles: number
  buySignals: number
  sellSignals: number
  holds: number
  warmupHolds: number
  conditionHolds: number
  riskBlocks: number
  fills: number
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
  changeRate: string
}

const MARKER_CAP = 220
const TOOLTIP_WIDTH = 184
const TOOLTIP_HEIGHT = 146

function payloadNumber(payload: Record<string, unknown>, key: string): number | null {
  const value = payload[key]
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function toChartTime(iso: string): UTCTimestamp | null {
  const time = Math.floor(new Date(iso).getTime() / 1000)
  return Number.isFinite(time) ? (time as UTCTimestamp) : null
}

function buildCandles(events: ScenarioEventDto[]): ReplayCandlePoint[] {
  const byTime = new Map<number, ReplayCandlePoint>()
  for (const event of events) {
    if (event.type !== 'CANDLE') continue
    const time = toChartTime(event.virtualTime)
    const open = payloadNumber(event.payload, 'open')
    const high = payloadNumber(event.payload, 'high')
    const low = payloadNumber(event.payload, 'low')
    const close = payloadNumber(event.payload, 'close')
    if (time === null || open === null || high === null || low === null || close === null) continue
    byTime.set(time, { time, iso: event.virtualTime, open, high, low, close })
  }
  return [...byTime.values()].sort((a, b) => a.time - b.time)
}

function buildMa(candles: ReplayCandlePoint[], period = 10): LineData[] {
  const points: LineData[] = []
  for (let index = period - 1; index < candles.length; index += 1) {
    const window = candles.slice(index - period + 1, index + 1)
    const value = window.reduce((sum, candle) => sum + candle.close, 0) / period
    points.push({ time: candles[index].time, value })
  }
  return points
}

function markerFor(event: ScenarioEventDto, includeHold: boolean): SeriesMarker<UTCTimestamp> | null {
  const time = toChartTime(event.virtualTime)
  if (time === null) return null

  if (event.type === 'SIGNAL_BUY') {
    return { time, position: 'belowBar', color: '#22c55e', shape: 'arrowUp', text: 'BUY' }
  }
  if (event.type === 'SIGNAL_SELL') {
    return { time, position: 'aboveBar', color: '#ef4444', shape: 'arrowDown', text: 'SELL' }
  }
  if (event.type === 'ORDER_FILLED') {
    const side = String(event.payload.side ?? '')
    return {
      time,
      position: side === 'SELL' ? 'aboveBar' : 'belowBar',
      color: side === 'SELL' ? '#f97316' : '#14b8a6',
      shape: 'circle',
      text: '체결',
    }
  }
  if (event.type === 'RISK_BLOCKED' || event.type === 'ORDER_REJECTED') {
    return { time, position: 'aboveBar', color: '#f59e0b', shape: 'square', text: '차단' }
  }
  if (event.type === 'ORDER_HELD') {
    return { time, position: 'aboveBar', color: '#a78bfa', shape: 'square', text: '보류' }
  }
  if (includeHold && event.type === 'SIGNAL_HOLD') {
    return {
      time,
      position: 'belowBar',
      color: event.message.includes('INSUFFICIENT_DATA') ? '#64748b' : '#94a3b8',
      shape: 'circle',
      text: 'HOLD',
    }
  }
  return null
}

function buildMarkers(events: ScenarioEventDto[], includeDiagnostics: boolean): SeriesMarker<UTCTimestamp>[] {
  const holdCount = events.filter((event) => event.type === 'SIGNAL_HOLD').length
  const includeHold = includeDiagnostics && holdCount <= MARKER_CAP
  return events
    .map((event) => markerFor(event, includeHold))
    .filter((marker): marker is SeriesMarker<UTCTimestamp> => marker !== null)
    .sort((a, b) => Number(a.time) - Number(b.time))
}

function buildSummary(events: ScenarioEventDto[]): EventSummary {
  return {
    candles: events.filter((event) => event.type === 'CANDLE').length,
    buySignals: events.filter((event) => event.type === 'SIGNAL_BUY').length,
    sellSignals: events.filter((event) => event.type === 'SIGNAL_SELL').length,
    holds: events.filter((event) => event.type === 'SIGNAL_HOLD').length,
    warmupHolds: events.filter((event) => event.type === 'SIGNAL_HOLD' && event.message.includes('INSUFFICIENT_DATA')).length,
    conditionHolds: events.filter((event) => event.type === 'SIGNAL_HOLD' && event.message.includes('COMPOSITE')).length,
    riskBlocks: events.filter((event) => event.type === 'RISK_BLOCKED').length,
    fills: events.filter((event) => event.type === 'ORDER_FILLED').length,
  }
}

function shortReason(message: string): string {
  const jsonStart = message.indexOf('{')
  if (jsonStart < 0) return message
  return message.slice(0, jsonStart).trim()
}

function firstKeyEvent(events: ScenarioEventDto[], includeDiagnostics: boolean): ScenarioEventDto | null {
  return events.find((event) => event.type === 'SIGNAL_BUY') ??
    events.find((event) => event.type === 'SIGNAL_SELL') ??
    events.find((event) => event.type === 'RISK_BLOCKED') ??
    events.find((event) => event.type === 'ORDER_FILLED') ??
    (includeDiagnostics ? events.find((event) => event.type === 'SIGNAL_HOLD') : undefined) ??
    null
}

function fillText(event: ScenarioEventDto | undefined): string | null {
  if (!event) return null
  const fillPrice = payloadNumber(event.payload, 'fillPrice')
  if (fillPrice === null) return formatKst(event.virtualTime)
  return `${formatKst(event.virtualTime)} · ${formatMoney(fillPrice)}`
}

export function ScenarioReplayChart({ run, events, showDiagnosticEvents }: ScenarioReplayChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const maSeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    x: 0,
    y: 0,
    timeLabel: '',
    open: 0,
    high: 0,
    low: 0,
    close: 0,
    changeRate: '0.00',
  })

  const candles = useMemo(() => buildCandles(events), [events])
  const usesMa10 = useMemo(() => events.some((event) => event.message.includes('ma(10)')), [events])
  const candleData = useMemo<CandlestickData[]>(
    () => candles.map((candle) => ({
      time: candle.time,
      open: candle.open,
      high: candle.high,
      low: candle.low,
      close: candle.close,
    })),
    [candles],
  )
  const maData = useMemo(() => (usesMa10 ? buildMa(candles) : []), [candles, usesMa10])
  const markers = useMemo(() => buildMarkers(events, showDiagnosticEvents), [events, showDiagnosticEvents])
  const chartInterval = useMemo(() => normalizeChartInterval(run.interval), [run.interval])
  const axisBoundaryTimes = useMemo(() => buildAxisBoundaryTimes(candleData.map((point) => point.time as UTCTimestamp)), [candleData])
  const summary = useMemo(() => buildSummary(events), [events])
  const keyEvent = useMemo(() => firstKeyEvent(events, showDiagnosticEvents), [events, showDiagnosticEvents])
  const firstFill = useMemo(() => events.find((event) => event.type === 'ORDER_FILLED'), [events])

  useEffect(() => {
    const container = containerRef.current
    if (!container || candleData.length === 0) return
    let visibleBars = candleData.length

    const chart = createChart(container, {
      width: container.clientWidth || 800,
      height: 360,
      layout: {
        background: { color: '#020617' },
        textColor: '#cbd5e1',
      },
      grid: {
        vertLines: { color: '#1e293b' },
        horzLines: { color: '#1e293b' },
      },
      crosshair: {
        mode: 1,
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
        dateFormat: 'yyyy-MM-dd',
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
    candleSeries.setData(candleData)
    candleSeries.setMarkers(markers)

    const maSeries = chart.addLineSeries({
      color: '#facc15',
      lineWidth: 2,
      priceLineVisible: false,
      lastValueVisible: false,
    })
    maSeries.setData(maData)

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

      const containerRect = container.getBoundingClientRect()
      const chartEl = container.querySelector('canvas')
      const offsetLeft = chartEl ? chartEl.getBoundingClientRect().left - containerRect.left : 0
      const offsetTop = chartEl ? chartEl.getBoundingClientRect().top - containerRect.top : 0
      const rawX = param.point.x + offsetLeft + 12
      const rawY = param.point.y + offsetTop + 12
      const x = rawX + TOOLTIP_WIDTH > container.clientWidth
        ? Math.max(8, param.point.x + offsetLeft - TOOLTIP_WIDTH - 12)
        : rawX
      const y = rawY + TOOLTIP_HEIGHT > container.clientHeight
        ? Math.max(8, param.point.y + offsetTop - TOOLTIP_HEIGHT - 12)
        : rawY
      const changeRate = candle.open !== 0
        ? (((candle.close - candle.open) / candle.open) * 100).toFixed(2)
        : '0.00'

      setTooltip({
        visible: true,
        x,
        y,
        timeLabel: formatKstCrosshairTime(param.time, chartInterval),
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
        changeRate,
      })
    })

    chart.timeScale().fitContent()
    chartRef.current = chart
    candleSeriesRef.current = candleSeries
    maSeriesRef.current = maSeries

    const resize = () => {
      const width = container.clientWidth || 800
      chart.applyOptions({ width })
    }
    window.addEventListener('resize', resize)

    return () => {
      window.removeEventListener('resize', resize)
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      maSeriesRef.current = null
    }
  }, [axisBoundaryTimes, candleData, chartInterval, maData, markers])

  if (candleData.length === 0) {
    return (
      <section className="rounded-lg border border-gray-800 bg-gray-950 p-4" aria-labelledby="scenario-chart-heading">
        <h2 id="scenario-chart-heading" className="text-sm font-semibold text-gray-200">
          리플레이 차트
        </h2>
        <div className="mt-3 flex h-48 items-center justify-center rounded border border-gray-800 bg-gray-900">
          <p className="text-sm text-gray-500">표시할 candle 이벤트가 없습니다</p>
        </div>
      </section>
    )
  }

  return (
    <section className="rounded-lg border border-gray-800 bg-gray-950 p-4" aria-labelledby="scenario-chart-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 id="scenario-chart-heading" className="text-sm font-semibold text-gray-200">
            리플레이 차트
          </h2>
          <div className="mt-1 text-xs text-gray-500">
            {run.code} · {run.interval} · {formatKst(run.rangeFrom)} ~ {formatKst(run.rangeTo)}
          </div>
        </div>
        <div className="flex flex-wrap gap-2 text-xs">
          {showDiagnosticEvents && (
            <span className="rounded border border-gray-700 px-2 py-1 text-gray-300">캔들 {summary.candles}</span>
          )}
          <span className="rounded border border-emerald-800 px-2 py-1 text-emerald-300">BUY {summary.buySignals}</span>
          {showDiagnosticEvents && (
            <span className="rounded border border-blue-800 px-2 py-1 text-blue-300">HOLD {summary.holds}</span>
          )}
          <span className="rounded border border-amber-800 px-2 py-1 text-amber-300">차단 {summary.riskBlocks}</span>
          <span className="rounded border border-teal-800 px-2 py-1 text-teal-300">체결 {summary.fills}</span>
          {usesMa10 && <span className="rounded border border-yellow-700 px-2 py-1 text-yellow-300">MA10</span>}
        </div>
      </div>

      <div className="relative mt-4 h-[360px] w-full overflow-hidden rounded border border-gray-800 bg-gray-950">
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
              <span className="text-gray-500">등락</span>
              <span className={Number(tooltip.changeRate) >= 0 ? 'text-right text-emerald-300' : 'text-right text-red-300'}>
                {Number(tooltip.changeRate) >= 0 ? '+' : ''}{tooltip.changeRate}%
              </span>
            </div>
          </div>
        )}
      </div>

      <div className={showDiagnosticEvents ? 'mt-3 grid gap-3 md:grid-cols-3' : 'mt-3 grid gap-3 md:grid-cols-2'}>
        {showDiagnosticEvents && (
          <div className="rounded border border-gray-800 bg-gray-900 px-3 py-2">
            <div className="text-xs text-gray-500">보류 요약</div>
            <div className="mt-1 text-sm text-gray-200">
              워밍업 {summary.warmupHolds.toLocaleString('ko-KR')} · 조건 불충족 {summary.conditionHolds.toLocaleString('ko-KR')}
            </div>
          </div>
        )}
        <div className="rounded border border-gray-800 bg-gray-900 px-3 py-2">
          <div className="text-xs text-gray-500">첫 핵심 이벤트</div>
          <div className="mt-1 text-sm text-gray-200">
            {keyEvent ? `${formatKst(keyEvent.virtualTime)} · ${eventLabel(keyEvent.type)}` : '-'}
          </div>
          {keyEvent && <div className="mt-1 text-xs text-gray-500">{shortReason(keyEvent.message)}</div>}
        </div>
        <div className="rounded border border-gray-800 bg-gray-900 px-3 py-2">
          <div className="text-xs text-gray-500">첫 가상 체결</div>
          <div className="mt-1 text-sm text-gray-200">{fillText(firstFill) ?? '-'}</div>
        </div>
      </div>
    </section>
  )
}
