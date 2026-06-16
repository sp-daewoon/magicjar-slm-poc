import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Coordinate, IChartApi, ISeriesApi, UTCTimestamp } from 'lightweight-charts'
import type { IndicatorResultDto } from '../../api/indicator'
import { INDICATOR_CATALOG_MAP } from '../../api/indicator'
import { useIndicators } from '../../api/hooks/useIndicators'
import type { CandleDto, CandleInterval } from '../../api/symbol'

export const SMART_MONEY_INDICATOR_IDS = new Set(['fvg', 'order_block'])

type SmartMoneyKind = 'fvg' | 'order_block'
type ZoneDirection = 'bullish' | 'bearish'

export interface SmartMoneyZone {
  id: string
  kind: SmartMoneyKind
  direction: ZoneDirection
  startTime: string
  endTime: string
  top: number
  bottom: number
  mid: number
  fillPct: number
  extendsRight: boolean
}

interface SmartMoneyZoneOverlayProps {
  chart: IChartApi | null
  series: ISeriesApi<'Candlestick'> | null
  code: string
  interval: CandleInterval
  candles: CandleDto[]
  selectedIds: Set<string>
  onErrorsChange?: (errors: Map<string, string>) => void
}

interface RenderedZone {
  zone: SmartMoneyZone
  x: number
  y: number
  width: number
  height: number
  fill: string
  stroke: string
  label: string
}

function paramsForIndicator(id: string): string | undefined {
  const catalogItem = INDICATOR_CATALOG_MAP.get(id)
  if (!catalogItem) return undefined
  const params = Object.entries(catalogItem.defaultParams)
    .map(([key, value]) => `${key}=${value}`)
    .join(',')
  return params || undefined
}

function parseNumber(value: string | undefined): number {
  if (value == null) return 0
  const parsed = Number.parseFloat(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function activeValue(value: string | undefined): boolean {
  return parseNumber(value) > 0
}

function sameZone(a: SmartMoneyZone, top: number, bottom: number): boolean {
  return Math.abs(a.top - top) < 0.0001 && Math.abs(a.bottom - bottom) < 0.0001
}

export function buildSmartMoneyZones(
  result: IndicatorResultDto | undefined,
  kind: SmartMoneyKind,
): SmartMoneyZone[] {
  if (!result?.series.length) return []

  const points = [...result.series].sort(
    (a, b) => new Date(a.time).getTime() - new Date(b.time).getTime(),
  )
  const zones: SmartMoneyZone[] = []

  for (const direction of ['bullish', 'bearish'] as const) {
    let current: SmartMoneyZone | null = null
    let previousTime: string | null = null

    for (const point of points) {
      const top = parseNumber(point.values[`${direction}_top`])
      const bottom = parseNumber(point.values[`${direction}_bottom`])
      const normalizedTop = Math.max(top, bottom)
      const normalizedBottom = Math.min(top, bottom)
      const isActive =
        activeValue(point.values[`${direction}_active`]) &&
        normalizedTop > 0 &&
        normalizedBottom > 0 &&
        normalizedTop > normalizedBottom

      if (!isActive) {
        if (current) {
          current.endTime = previousTime ?? point.time
          zones.push(current)
          current = null
        }
        previousTime = point.time
        continue
      }

      if (current && sameZone(current, normalizedTop, normalizedBottom)) {
        current.endTime = point.time
        current.mid = parseNumber(point.values[`${direction}_mid`]) || current.mid
        current.fillPct = parseNumber(point.values[`${direction}_fill_pct`])
        previousTime = point.time
        continue
      }

      if (current) {
        current.endTime = previousTime ?? current.endTime
        zones.push(current)
      }

      current = {
        id: `${kind}-${direction}-${zones.length}-${point.time}`,
        kind,
        direction,
        startTime: point.time,
        endTime: point.time,
        top: normalizedTop,
        bottom: normalizedBottom,
        mid: parseNumber(point.values[`${direction}_mid`]) || (normalizedTop + normalizedBottom) / 2,
        fillPct: parseNumber(point.values[`${direction}_fill_pct`]),
        extendsRight: false,
      }
      previousTime = point.time
    }

    if (current) {
      current.extendsRight = true
      zones.push(current)
    }
  }

  return zones.sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
}

function toTimestamp(isoString: string): UTCTimestamp | null {
  const ms = new Date(isoString).getTime()
  if (!Number.isFinite(ms)) return null
  return Math.floor(ms / 1000) as UTCTimestamp
}

function findBracketIndex(times: number[], target: number): number {
  let low = 0
  let high = times.length - 1
  while (low <= high) {
    const mid = Math.floor((low + high) / 2)
    if (times[mid] < target) {
      low = mid + 1
    } else {
      high = mid - 1
    }
  }
  return low
}

function timeToCoordinate(
  chart: IChartApi,
  candles: CandleDto[],
  time: UTCTimestamp,
): Coordinate | null {
  const direct = chart.timeScale().timeToCoordinate(time)
  if (direct != null) return direct
  if (candles.length === 0) return null

  const candleTimes = candles
    .map((candle) => toTimestamp(candle.ts))
    .filter((value): value is UTCTimestamp => value !== null)
    .map(Number)
  const target = Number(time)
  const bracketIndex = findBracketIndex(candleTimes, target)
  const lowerIndex = bracketIndex - 1
  const upperIndex = bracketIndex
  if (lowerIndex < 0 || upperIndex >= candleTimes.length) return null

  const lowerTime = candleTimes[lowerIndex]
  const upperTime = candleTimes[upperIndex]
  if (upperTime === lowerTime) return null

  const lowerX = chart.timeScale().timeToCoordinate(lowerTime as UTCTimestamp)
  const upperX = chart.timeScale().timeToCoordinate(upperTime as UTCTimestamp)
  if (lowerX == null || upperX == null) return null

  const ratio = (target - lowerTime) / (upperTime - lowerTime)
  return (Number(lowerX) + (Number(upperX) - Number(lowerX)) * ratio) as Coordinate
}

function estimateBarWidth(chart: IChartApi, candles: CandleDto[]): number {
  const coordinates = candles
    .slice(-120)
    .map((candle) => {
      const ts = toTimestamp(candle.ts)
      return ts == null ? null : chart.timeScale().timeToCoordinate(ts)
    })
    .filter((value): value is Coordinate => value !== null)
    .map(Number)
    .sort((a, b) => a - b)

  const gaps: number[] = []
  for (let i = 1; i < coordinates.length; i += 1) {
    const gap = coordinates[i] - coordinates[i - 1]
    if (gap > 0) gaps.push(gap)
  }
  if (gaps.length === 0) return 8

  const median = gaps[Math.floor(gaps.length / 2)]
  return Math.min(Math.max(median * 0.9, 4), 36)
}

function addOneSecond(isoString: string): string {
  const ms = new Date(isoString).getTime()
  if (!Number.isFinite(ms)) return isoString
  return new Date(ms + 1000).toISOString()
}

function zoneColors(zone: SmartMoneyZone): Pick<RenderedZone, 'fill' | 'stroke' | 'label'> {
  if (zone.kind === 'fvg' && zone.direction === 'bullish') {
    return { fill: '#10b981', stroke: '#34d399', label: 'FVG BULL' }
  }
  if (zone.kind === 'fvg' && zone.direction === 'bearish') {
    return { fill: '#f43f5e', stroke: '#fb7185', label: 'FVG BEAR' }
  }
  if (zone.kind === 'order_block' && zone.direction === 'bullish') {
    return { fill: '#0ea5e9', stroke: '#38bdf8', label: 'OB BULL' }
  }
  return { fill: '#f59e0b', stroke: '#fbbf24', label: 'OB BEAR' }
}

export function SmartMoneyZoneOverlay({
  chart,
  series,
  code,
  interval,
  candles,
  selectedIds,
  onErrorsChange,
}: SmartMoneyZoneOverlayProps) {
  const svgRef = useRef<SVGSVGElement | null>(null)
  const errorsRef = useRef<Map<string, string>>(new Map())
  const [overlayWidth, setOverlayWidth] = useState(0)
  const [viewportVersion, setViewportVersion] = useState(0)

  const showFvg = selectedIds.has('fvg')
  const showOrderBlock = selectedIds.has('order_block')
  const from = candles[0]?.ts
  const to = candles.length > 0 ? addOneSecond(candles[candles.length - 1].ts) : undefined

  const fvgResult = useIndicators({
    code,
    name: 'fvg',
    interval,
    params: paramsForIndicator('fvg'),
    from,
    to,
    enabled: Boolean(chart) && Boolean(series) && showFvg && candles.length > 0,
  })
  const orderBlockResult = useIndicators({
    code,
    name: 'order_block',
    interval,
    params: paramsForIndicator('order_block'),
    from,
    to,
    enabled: Boolean(chart) && Boolean(series) && showOrderBlock && candles.length > 0,
  })

  const updateError = useCallback(
    (id: string, message: string | null) => {
      const current = errorsRef.current
      if (message == null) {
        if (!current.has(id)) return
        const next = new Map(current)
        next.delete(id)
        errorsRef.current = next
      } else {
        if (current.get(id) === message) return
        const next = new Map(current)
        next.set(id, message)
        errorsRef.current = next
      }
      onErrorsChange?.(new Map(errorsRef.current))
    },
    [onErrorsChange],
  )

  useEffect(() => {
    updateError(
      'fvg',
      showFvg && fvgResult.isError ? fvgResult.errorMessage ?? 'FVG 로드 실패' : null,
    )
  }, [fvgResult.errorMessage, fvgResult.isError, showFvg, updateError])

  useEffect(() => {
    updateError(
      'order_block',
      showOrderBlock && orderBlockResult.isError
        ? orderBlockResult.errorMessage ?? 'Order Block 로드 실패'
        : null,
    )
  }, [orderBlockResult.errorMessage, orderBlockResult.isError, showOrderBlock, updateError])

  useEffect(() => {
    if (!chart) return
    const rerender = () => setViewportVersion((version) => version + 1)
    chart.timeScale().subscribeVisibleLogicalRangeChange(rerender)
    return () => {
      try {
        chart.timeScale().unsubscribeVisibleLogicalRangeChange(rerender)
      } catch {
        // chart dispose race graceful
      }
    }
  }, [chart])

  useEffect(() => {
    const svg = svgRef.current
    if (!svg) return

    const updateSize = () => {
      setOverlayWidth(svg.getBoundingClientRect().width)
    }
    updateSize()

    const observer = new ResizeObserver(updateSize)
    observer.observe(svg)
    return () => {
      observer.disconnect()
    }
  }, [chart, series, showFvg, showOrderBlock])

  const zones = useMemo(() => {
    const next: SmartMoneyZone[] = []
    if (showFvg) next.push(...buildSmartMoneyZones(fvgResult.data, 'fvg'))
    if (showOrderBlock) {
      next.push(...buildSmartMoneyZones(orderBlockResult.data, 'order_block'))
    }
    return next
      .sort((a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime())
      .slice(-180)
  }, [fvgResult.data, orderBlockResult.data, showFvg, showOrderBlock])

  const renderedZones = useMemo<RenderedZone[]>(() => {
    if (!chart || !series || overlayWidth <= 0) return []
    void viewportVersion

    const barWidth = estimateBarWidth(chart, candles)
    return zones
      .map<RenderedZone | null>((zone) => {
        const startTs = toTimestamp(zone.startTime)
        const endTs = toTimestamp(zone.endTime)
        if (startTs == null || endTs == null) return null

        const startX = timeToCoordinate(chart, candles, startTs)
        const endX = zone.extendsRight ? (overlayWidth as Coordinate) : timeToCoordinate(chart, candles, endTs)
        let x1 = startX
        let x2 = endX
        if (x1 == null && x2 != null) x1 = 0 as Coordinate
        if (x2 == null && x1 != null) x2 = overlayWidth as Coordinate
        if (x1 == null || x2 == null) return null

        let left = Math.min(Number(x1), Number(x2))
        let right = zone.extendsRight ? overlayWidth : Math.max(Number(x1), Number(x2))
        if (!zone.extendsRight && right - left < barWidth) {
          right = left + barWidth
        }
        left = Math.max(left, 0)
        right = Math.min(Math.max(right, left + 4), overlayWidth)
        if (right <= 0 || left >= overlayWidth) return null

        const topY = series.priceToCoordinate(zone.top)
        const bottomY = series.priceToCoordinate(zone.bottom)
        if (topY == null || bottomY == null) return null

        const y = Math.min(Number(topY), Number(bottomY))
        const height = Math.max(Math.abs(Number(bottomY) - Number(topY)), 5)
        const color = zoneColors(zone)

        return {
          zone,
          x: left,
          y,
          width: right - left,
          height,
          ...color,
        }
      })
      .filter((item): item is RenderedZone => item !== null)
  }, [candles, chart, overlayWidth, series, viewportVersion, zones])

  if (!chart || !series || (!showFvg && !showOrderBlock)) return null

  return (
    <div className="pointer-events-none absolute inset-0 z-10">
      <svg
        ref={svgRef}
        className="absolute inset-0 h-full w-full overflow-hidden"
        style={{ pointerEvents: 'none' }}
        aria-hidden="true"
      >
        {renderedZones.map((item) => (
          <g key={item.zone.id}>
            <rect
              x={item.x}
              y={item.y}
              width={item.width}
              height={item.height}
              rx={2}
              fill={item.fill}
              fillOpacity={item.zone.kind === 'fvg' ? 0.16 : 0.2}
              stroke={item.stroke}
              strokeOpacity={0.82}
              strokeWidth={1}
              strokeDasharray={item.zone.kind === 'fvg' ? '5 4' : undefined}
            />
            {item.width >= 62 && item.height >= 16 && (
              <text
                x={item.x + 6}
                y={item.y + 13}
                fill={item.stroke}
                opacity={0.9}
                fontSize={10}
                fontWeight={700}
              >
                {item.label}
              </text>
            )}
          </g>
        ))}
      </svg>
    </div>
  )
}
