import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Coordinate, IChartApi, ISeriesApi, UTCTimestamp } from 'lightweight-charts'
import type { CandleDto } from '../../api/symbol'
import type { TrendlineCreateRequest, TrendlineDto, TrendlineUpdateRequest } from '../../api/trendlines'

interface TrendlineOverlayProps {
  chart: IChartApi | null
  series: ISeriesApi<'Candlestick'> | null
  trendlines: TrendlineDto[]
  candles: CandleDto[]
  isDrawingMode?: boolean
  drawingType?: TrendlineDto['type']
  drawingAnchor?: TrendlineDrawPoint | null
  drawingPoint?: TrendlineDrawPoint | null
  onDrawingPointClick?: (point: TrendlineDrawPoint) => void
  onDrawingPointMove?: (point: TrendlineDrawPoint | null) => void
  onUpdate?: (id: number, request: TrendlineUpdateRequest) => void
  onDelete?: (id: number) => void
  onDuplicate?: (request: TrendlineCreateRequest) => void
}

type DragMode = 'anchor1' | 'anchor2' | 'line'
type ExtensionMode = 'none' | 'right' | 'left' | 'both'
type ChartTime = UTCTimestamp | string | { year: number; month: number; day: number } | null

interface ChartPoint {
  ts: string
  price: number
}

interface CanvasPoint {
  x: number
  y: number
}

interface TrendlineDrawPoint {
  ts: string
  price: string
}

interface DragState {
  id: number
  mode: DragMode
  startPoint: ChartPoint
  original: TrendlineDto
}

interface RenderedTrendline {
  trendline: TrendlineDto
  x1: Coordinate
  y1: Coordinate
  x2: Coordinate
  y2: Coordinate
  anchor1X: Coordinate | null
  anchor1Y: Coordinate | null
  anchor2X: Coordinate | null
  anchor2Y: Coordinate | null
}

interface DrawingPreview {
  color: string
  cursorX: Coordinate
  cursorY: Coordinate
  anchorX: Coordinate | null
  anchorY: Coordinate | null
}

const COLOR_SWATCHES = ['#2563eb', '#10b981', '#ef4444', '#f59e0b', '#a855f7', '#f8fafc']

function toTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

function toIsoFromChartTime(time: ChartTime): string | null {
  if (!time) return null
  if (typeof time === 'number') return new Date(time * 1000).toISOString()
  if (typeof time === 'string') {
    const ms = new Date(time).getTime()
    return Number.isFinite(ms) ? new Date(ms).toISOString() : null
  }
  const businessDay = time as { year?: number; month?: number; day?: number }
  if (businessDay.year && businessDay.month && businessDay.day) {
    return new Date(Date.UTC(businessDay.year, businessDay.month - 1, businessDay.day)).toISOString()
  }
  return null
}

function defaultColor(type: TrendlineDto['type']): string {
  return type === 'SUPPORT' ? '#10b981' : '#ef4444'
}

function extensionModeOf(trendline: TrendlineDto): ExtensionMode {
  if (trendline.extendLeft && trendline.extendRight) return 'both'
  if (trendline.extendLeft) return 'left'
  if (trendline.extendRight) return 'right'
  return 'none'
}

function extensionFlags(mode: ExtensionMode): Pick<TrendlineUpdateRequest, 'extendLeft' | 'extendRight'> {
  return {
    extendLeft: mode === 'left' || mode === 'both',
    extendRight: mode === 'right' || mode === 'both',
  }
}

function extensionLabel(mode: ExtensionMode): string {
  if (mode === 'both') return '양방향 익스텐드'
  if (mode === 'left') return '왼쪽 익스텐드'
  if (mode === 'right') return '오른쪽 익스텐드'
  return '익스텐드 없음'
}

function toUpdateRequest(trendline: TrendlineDto): TrendlineUpdateRequest {
  return {
    type: trendline.type,
    anchor1Ts: trendline.anchor1Ts,
    anchor1Price: trendline.anchor1Price,
    anchor2Ts: trendline.anchor2Ts,
    anchor2Price: trendline.anchor2Price,
    extendLeft: trendline.extendLeft ?? false,
    extendRight: trendline.extendRight,
    label: trendline.label ?? null,
    color: trendline.color ?? null,
  }
}

function addSeconds(isoString: string, seconds: number): string {
  return new Date(new Date(isoString).getTime() + seconds * 1000).toISOString()
}

function formatPrice(price: number): string {
  return price.toFixed(4)
}

function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  const tagName = target.tagName
  return (
    tagName === 'INPUT' ||
    tagName === 'TEXTAREA' ||
    tagName === 'SELECT' ||
    target.isContentEditable
  )
}

function candlePrices(candle: CandleDto): number[] {
  return [candle.open, candle.high, candle.low, candle.close]
    .map(Number)
    .filter((value) => Number.isFinite(value))
}

function duplicatePriceShift(trendline: TrendlineDto, candles: CandleDto[]): number {
  const prices = candles
    .flatMap((candle) => [Number(candle.high), Number(candle.low)])
    .filter((price) => Number.isFinite(price))
  if (prices.length >= 2) {
    const min = Math.min(...prices)
    const max = Math.max(...prices)
    const range = max - min
    if (range > 0) return range * 0.035
  }

  const anchor1Price = Number(trendline.anchor1Price)
  const anchor2Price = Number(trendline.anchor2Price)
  const anchorRange = Math.abs(anchor2Price - anchor1Price)
  if (anchorRange > 0) return anchorRange * 0.2
  return Math.max(Math.abs(anchor1Price) * 0.01, 1)
}

function toDuplicateRequest(
  trendline: TrendlineDto,
  candles: CandleDto[],
): TrendlineCreateRequest {
  const priceShift = duplicatePriceShift(trendline, candles)
  return {
    sourceInterval: trendline.sourceInterval,
    type: trendline.type,
    anchor1Ts: trendline.anchor1Ts,
    anchor1Price: formatPrice(Number(trendline.anchor1Price) + priceShift),
    anchor2Ts: trendline.anchor2Ts,
    anchor2Price: formatPrice(Number(trendline.anchor2Price) + priceShift),
    extendLeft: trendline.extendLeft ?? false,
    extendRight: trendline.extendRight,
    label: trendline.label ?? null,
    color: trendline.color ?? null,
  }
}

function applyTrendlineDrag(drag: DragState, point: ChartPoint): TrendlineDto {
  const original = drag.original

  if (drag.mode === 'anchor1') {
    return {
      ...original,
      anchor1Ts: point.ts,
      anchor1Price: formatPrice(point.price),
    }
  }

  if (drag.mode === 'anchor2') {
    return {
      ...original,
      anchor2Ts: point.ts,
      anchor2Price: formatPrice(point.price),
    }
  }

  const timeDeltaSeconds = Math.round(
    (new Date(point.ts).getTime() - new Date(drag.startPoint.ts).getTime()) / 1000,
  )
  const priceDelta = point.price - drag.startPoint.price
  return {
    ...original,
    anchor1Ts: addSeconds(original.anchor1Ts, timeDeltaSeconds),
    anchor1Price: formatPrice(Number(original.anchor1Price) + priceDelta),
    anchor2Ts: addSeconds(original.anchor2Ts, timeDeltaSeconds),
    anchor2Price: formatPrice(Number(original.anchor2Price) + priceDelta),
  }
}

function hasTrendlineChanged(before: TrendlineDto, after: TrendlineDto): boolean {
  return (
    before.type !== after.type ||
    before.anchor1Ts !== after.anchor1Ts ||
    before.anchor1Price !== after.anchor1Price ||
    before.anchor2Ts !== after.anchor2Ts ||
    before.anchor2Price !== after.anchor2Price ||
    Boolean(before.extendLeft) !== Boolean(after.extendLeft) ||
    Boolean(before.extendRight) !== Boolean(after.extendRight) ||
    (before.label ?? null) !== (after.label ?? null) ||
    (before.color ?? null) !== (after.color ?? null)
  )
}

function lineHitStyle(item: RenderedTrendline) {
  const dx = Number(item.x2) - Number(item.x1)
  const dy = Number(item.y2) - Number(item.y1)
  const length = Math.max(Math.hypot(dx, dy), 1)
  const angle = Math.atan2(dy, dx) * (180 / Math.PI)
  return {
    left: `${Number(item.x1)}px`,
    top: `${Number(item.y1)}px`,
    width: `${length}px`,
    transform: `translateY(-50%) rotate(${angle}deg)`,
    transformOrigin: '0 50%',
  }
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
  time: number,
): Coordinate | null {
  const direct = chart.timeScale().timeToCoordinate(time as UTCTimestamp)
  if (direct != null) return direct
  if (candles.length === 0) return null

  const candleTimes = candles.map((candle) => toTimestamp(candle.ts) as number)
  const bracketIndex = findBracketIndex(candleTimes, time)
  const lowerIndex = bracketIndex - 1
  const upperIndex = bracketIndex
  if (lowerIndex < 0 || upperIndex >= candleTimes.length) return null

  const lowerTime = candleTimes[lowerIndex]
  const upperTime = candleTimes[upperIndex]
  if (upperTime === lowerTime) return null

  const lowerX = chart.timeScale().timeToCoordinate(lowerTime as UTCTimestamp)
  const upperX = chart.timeScale().timeToCoordinate(upperTime as UTCTimestamp)
  if (lowerX == null || upperX == null) return null

  const ratio = (time - lowerTime) / (upperTime - lowerTime)
  return (Number(lowerX) + (Number(upperX) - Number(lowerX)) * ratio) as Coordinate
}

function snapPointToCandle(
  chart: IChartApi,
  series: ISeriesApi<'Candlestick'>,
  candles: CandleDto[],
  point: CanvasPoint,
): ChartPoint | null {
  if (candles.length === 0) return null

  let nearestCandle: CandleDto | null = null
  let nearestDistance = Number.POSITIVE_INFINITY

  for (const candle of candles) {
    const x = chart.timeScale().timeToCoordinate(toTimestamp(candle.ts))
    if (x == null) continue
    const distance = Math.abs(Number(x) - point.x)
    if (distance < nearestDistance) {
      nearestDistance = distance
      nearestCandle = candle
    }
  }

  if (!nearestCandle) return null

  const prices = candlePrices(nearestCandle)
  if (prices.length === 0) return null

  let nearestPrice = prices[0]
  let nearestPriceDistance = Number.POSITIVE_INFINITY
  for (const price of prices) {
    const y = series.priceToCoordinate(price)
    if (y == null) continue
    const distance = Math.abs(Number(y) - point.y)
    if (distance < nearestPriceDistance) {
      nearestPriceDistance = distance
      nearestPrice = price
    }
  }

  return {
    ts: nearestCandle.ts,
    price: nearestPrice,
  }
}

function extrapolateY(anchorX: Coordinate, anchorY: Coordinate, slope: number, targetX: number): Coordinate {
  return (Number(anchorY) + slope * (targetX - Number(anchorX))) as Coordinate
}

export function TrendlineOverlay({
  chart,
  series,
  trendlines,
  candles,
  isDrawingMode = false,
  drawingType = 'SUPPORT',
  drawingAnchor = null,
  drawingPoint = null,
  onDrawingPointClick,
  onDrawingPointMove,
  onUpdate,
  onDelete,
  onDuplicate,
}: TrendlineOverlayProps) {
  const svgRef = useRef<SVGSVGElement | null>(null)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [dragState, setDragState] = useState<DragState | null>(null)
  const [draftMap, setDraftMap] = useState<Map<number, TrendlineDto>>(new Map())
  const draftMapRef = useRef(draftMap)
  const [copiedTrendline, setCopiedTrendline] = useState<TrendlineDto | null>(null)
  const [overlayWidth, setOverlayWidth] = useState(0)
  const [viewportVersion, setViewportVersion] = useState(0)
  const [editing, setEditing] = useState<TrendlineDto | null>(null)
  const [editDraft, setEditDraft] = useState<TrendlineDto | null>(null)
  const [activeTab, setActiveTab] = useState<'appearance' | 'coordinates' | 'visibility'>('appearance')

  useEffect(() => {
    if (!dragState) setDraftMap(new Map())
  }, [trendlines, dragState])

  useEffect(() => {
    draftMapRef.current = draftMap
  }, [draftMap])

  useEffect(() => {
    if (!editing) {
      setEditDraft(null)
      return
    }
    setEditDraft(editing)
    setActiveTab('appearance')
  }, [editing])

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
  }, [chart, series])

  useEffect(() => {
    if ((selectedId == null && !copiedTrendline) || editing) return

    const handleKeyDown = (event: KeyboardEvent) => {
      if (isEditableTarget(event.target)) return

      const key = event.key.toLowerCase()
      const isShortcut = event.ctrlKey || event.metaKey
      const selectedTrendline =
        selectedId == null
          ? null
          : (draftMapRef.current.get(selectedId) ??
            trendlines.find((trendline) => trendline.id === selectedId) ??
            null)

      if (isShortcut && key === 'c' && selectedTrendline) {
        event.preventDefault()
        event.stopPropagation()
        setCopiedTrendline(selectedTrendline)
        return
      }

      if (isShortcut && key === 'v') {
        const source = copiedTrendline ?? selectedTrendline
        if (!source) return
        event.preventDefault()
        event.stopPropagation()
        onDuplicate?.(toDuplicateRequest(source, candles))
        return
      }

      if (event.key !== 'Delete' && event.key !== 'Backspace') return
      if (selectedId == null) return

      event.preventDefault()
      event.stopPropagation()
      onDelete?.(selectedId)
      setSelectedId(null)
      setDraftMap((prev) => {
        if (!prev.has(selectedId)) return prev
        const next = new Map(prev)
        next.delete(selectedId)
        return next
      })
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [candles, copiedTrendline, editing, onDelete, onDuplicate, selectedId, trendlines])

  const displayedTrendlines = useMemo(
    () => trendlines.map((trendline) => draftMap.get(trendline.id) ?? trendline),
    [trendlines, draftMap],
  )

  const renderedTrendlines = useMemo<RenderedTrendline[]>(() => {
    if (!chart || !series) return []
    void viewportVersion
    return displayedTrendlines
      .map<RenderedTrendline | null>((trendline) => {
        const anchor1Time = toTimestamp(trendline.anchor1Ts)
        const anchor2Time = toTimestamp(trendline.anchor2Ts)
        const anchor1X = timeToCoordinate(chart, candles, anchor1Time as number)
        const anchor2X = timeToCoordinate(chart, candles, anchor2Time as number)
        const anchor1Y = series.priceToCoordinate(Number(trendline.anchor1Price))
        const anchor2Y = series.priceToCoordinate(Number(trendline.anchor2Price))
        if (anchor1X == null || anchor2X == null || anchor1Y == null || anchor2Y == null) return null

        let x1 = anchor1X
        let y1 = anchor1Y
        let x2 = anchor2X
        let y2 = anchor2Y
        const dx = Number(anchor2X) - Number(anchor1X)
        if (dx !== 0) {
          const slope = (Number(anchor2Y) - Number(anchor1Y)) / dx
          if (trendline.extendLeft) {
            x1 = 0 as Coordinate
            y1 = extrapolateY(anchor1X, anchor1Y, slope, 0)
          }
          if (trendline.extendRight && overlayWidth > 0) {
            x2 = overlayWidth as Coordinate
            y2 = extrapolateY(anchor1X, anchor1Y, slope, overlayWidth)
          }
        }

        return {
          trendline,
          x1,
          y1,
          x2,
          y2,
          anchor1X,
          anchor1Y,
          anchor2X,
          anchor2Y,
        } satisfies RenderedTrendline
      })
      .filter((item): item is RenderedTrendline => item !== null)
  }, [chart, series, displayedTrendlines, candles, overlayWidth, viewportVersion])

  const drawingPreview = useMemo<DrawingPreview | null>(() => {
    if (!chart || !series || !isDrawingMode || !drawingPoint) return null

    const cursorPrice = Number(drawingPoint.price)
    if (!Number.isFinite(cursorPrice)) return null

    const cursorX = timeToCoordinate(chart, candles, toTimestamp(drawingPoint.ts) as number)
    const cursorY = series.priceToCoordinate(cursorPrice)
    if (cursorX == null || cursorY == null) return null

    let anchorX: Coordinate | null = null
    let anchorY: Coordinate | null = null
    if (drawingAnchor) {
      const anchorPrice = Number(drawingAnchor.price)
      anchorX = timeToCoordinate(chart, candles, toTimestamp(drawingAnchor.ts) as number)
      anchorY = Number.isFinite(anchorPrice) ? series.priceToCoordinate(anchorPrice) : null
    }

    return {
      color: defaultColor(drawingType),
      cursorX,
      cursorY,
      anchorX,
      anchorY,
    }
  }, [candles, chart, drawingAnchor, drawingPoint, drawingType, isDrawingMode, series])

  const eventToCanvasPoint = useCallback((event: PointerEvent | React.PointerEvent<Element> | React.MouseEvent<Element>) => {
    const rect = svgRef.current?.getBoundingClientRect()
    if (!rect) return null
    return {
      x: event.clientX - rect.left,
      y: event.clientY - rect.top,
    }
  }, [])

  const eventToChartPoint = useCallback(
    (event: PointerEvent | React.PointerEvent<Element> | React.MouseEvent<Element>): ChartPoint | null => {
      if (!chart || !series) return null
      const point = eventToCanvasPoint(event)
      if (!point) return null
      if (event.ctrlKey || event.metaKey) {
        const snappedPoint = snapPointToCandle(chart, series, candles, point)
        if (snappedPoint) return snappedPoint
      }
      const time = chart.timeScale().coordinateToTime(point.x) as ChartTime
      const price = series.coordinateToPrice(point.y)
      if (price == null || !Number.isFinite(price)) return null
      const ts = toIsoFromChartTime(time)
      if (!ts) return null
      return { ts, price }
    },
    [candles, chart, series, eventToCanvasPoint],
  )

  const updateDraft = useCallback((drag: DragState, point: ChartPoint) => {
    setDraftMap((prev) => {
      const next = new Map(prev)
      next.set(drag.original.id, applyTrendlineDrag(drag, point))
      return next
    })
  }, [])

  const handlePointerDown = useCallback(
    (
      event: React.PointerEvent<Element>,
      trendline: TrendlineDto,
      mode: DragMode,
    ) => {
      const point = eventToChartPoint(event)
      if (!point) return
      event.preventDefault()
      event.stopPropagation()
      setSelectedId(trendline.id)
      setDragState({ id: trendline.id, mode, startPoint: point, original: trendline })
    },
    [eventToChartPoint],
  )

  const handleDrawingPointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (!isDrawingMode) return
      const point = eventToChartPoint(event)
      onDrawingPointMove?.(point ? { ts: point.ts, price: formatPrice(point.price) } : null)
    },
    [eventToChartPoint, isDrawingMode, onDrawingPointMove],
  )

  const handleDrawingPointerLeave = useCallback(() => {
    onDrawingPointMove?.(null)
  }, [onDrawingPointMove])

  const handleDrawingClick = useCallback(
    (event: React.MouseEvent<HTMLDivElement>) => {
      if (!isDrawingMode) return
      const point = eventToChartPoint(event)
      if (!point) return
      event.preventDefault()
      event.stopPropagation()
      onDrawingPointClick?.({ ts: point.ts, price: formatPrice(point.price) })
    },
    [eventToChartPoint, isDrawingMode, onDrawingPointClick],
  )

  useEffect(() => {
    if (!dragState) return
    const handleMove = (event: PointerEvent) => {
      const point = eventToChartPoint(event)
      if (!point) return
      event.preventDefault()
      updateDraft(dragState, point)
    }
    const handleUp = (event: PointerEvent) => {
      const point = eventToChartPoint(event)
      const finalDraft = point
        ? applyTrendlineDrag(dragState, point)
        : draftMapRef.current.get(dragState.id)
      if (finalDraft) {
        setDraftMap((prev) => {
          const next = new Map(prev)
          next.set(dragState.id, finalDraft)
          return next
        })
      }
      setDragState(null)
      if (
        finalDraft &&
        finalDraft.anchor1Ts !== finalDraft.anchor2Ts &&
        hasTrendlineChanged(dragState.original, finalDraft)
      ) {
        onUpdate?.(dragState.id, toUpdateRequest(finalDraft))
      }
    }
    window.addEventListener('pointermove', handleMove)
    window.addEventListener('pointerup', handleUp, { once: true })
    return () => {
      window.removeEventListener('pointermove', handleMove)
      window.removeEventListener('pointerup', handleUp)
    }
  }, [dragState, eventToChartPoint, onUpdate, updateDraft])

  const openEditor = useCallback((trendline: TrendlineDto) => {
    setSelectedId(trendline.id)
    setEditing(trendline)
  }, [])

  const commitEditor = useCallback(() => {
    if (!editDraft) return
    onUpdate?.(editDraft.id, toUpdateRequest(editDraft))
    setEditing(null)
  }, [editDraft, onUpdate])

  if (!chart || !series || (renderedTrendlines.length === 0 && !drawingPreview && !isDrawingMode)) {
    return null
  }

  return (
    <>
      <div className="pointer-events-none absolute inset-0 z-20">
        <svg
          ref={svgRef}
          className="absolute inset-0 h-full w-full overflow-hidden"
          style={{ pointerEvents: 'none' }}
          aria-hidden="true"
        >
          {renderedTrendlines.map((item) => {
            const { trendline } = item
            const color = trendline.color ?? defaultColor(trendline.type)
            const isSelected = selectedId === trendline.id || dragState?.id === trendline.id
            return (
              <g key={trendline.id}>
                <line
                  x1={item.x1}
                  y1={item.y1}
                  x2={item.x2}
                  y2={item.y2}
                  stroke={color}
                  strokeWidth={trendline.source === 'AUTO' ? 1.5 : 2.5}
                  strokeDasharray={trendline.source === 'AUTO' ? '3 4' : undefined}
                />
                {isSelected && item.anchor1X != null && item.anchor1Y != null && (
                  <circle
                    cx={item.anchor1X}
                    cy={item.anchor1Y}
                    r={8}
                    fill="#f8fafc"
                    stroke={color}
                    strokeWidth={3}
                  />
                )}
                {isSelected && item.anchor2X != null && item.anchor2Y != null && (
                  <circle
                    cx={item.anchor2X}
                    cy={item.anchor2Y}
                    r={8}
                    fill="#f8fafc"
                    stroke={color}
                    strokeWidth={3}
                  />
                )}
              </g>
            )
          })}

          {drawingPreview && (
            <g>
              {drawingPreview.anchorX != null && drawingPreview.anchorY != null && (
                <>
                  <line
                    x1={drawingPreview.anchorX}
                    y1={drawingPreview.anchorY}
                    x2={drawingPreview.cursorX}
                    y2={drawingPreview.cursorY}
                    stroke={drawingPreview.color}
                    strokeWidth={2}
                    strokeDasharray="6 5"
                    opacity={0.9}
                  />
                  <circle
                    cx={drawingPreview.anchorX}
                    cy={drawingPreview.anchorY}
                    r={7}
                    fill="#0d1117"
                    stroke={drawingPreview.color}
                    strokeWidth={2.5}
                  />
                </>
              )}
              <circle
                cx={drawingPreview.cursorX}
                cy={drawingPreview.cursorY}
                r={9}
                fill="#0d1117"
                stroke={drawingPreview.color}
                strokeWidth={2.5}
                opacity={0.95}
              />
              <circle
                cx={drawingPreview.cursorX}
                cy={drawingPreview.cursorY}
                r={3}
                fill={drawingPreview.color}
              />
            </g>
          )}
        </svg>

        {renderedTrendlines.map((item) => {
          const { trendline } = item
          const color = trendline.color ?? defaultColor(trendline.type)
          const isSelected = selectedId === trendline.id || dragState?.id === trendline.id
          return (
            <div key={trendline.id}>
              <button
                type="button"
                tabIndex={-1}
                className={`absolute h-4 cursor-move border-0 bg-transparent p-0 outline-none ${
                  isDrawingMode ? 'pointer-events-none' : 'pointer-events-auto'
                }`}
                style={lineHitStyle(item)}
                aria-label="추세선 선택"
                onPointerDown={(event) => handlePointerDown(event, trendline, 'line')}
                onClick={(event) => {
                  event.stopPropagation()
                  setSelectedId(trendline.id)
                }}
                onDoubleClick={(event) => {
                  event.stopPropagation()
                  openEditor(trendline)
                }}
              />
              {isSelected && item.anchor1X != null && item.anchor1Y != null && (
                <button
                  type="button"
                  tabIndex={-1}
                  className={`absolute h-4 w-4 cursor-grab rounded-full border-[3px] outline-none active:cursor-grabbing ${
                    isDrawingMode ? 'pointer-events-none' : 'pointer-events-auto'
                  }`}
                  style={{
                    left: `${Number(item.anchor1X) - 8}px`,
                    top: `${Number(item.anchor1Y) - 8}px`,
                    borderColor: color,
                    backgroundColor: '#f8fafc',
                  }}
                  aria-label="추세선 시작점 이동"
                  onPointerDown={(event) => handlePointerDown(event, trendline, 'anchor1')}
                  onDoubleClick={(event) => {
                    event.stopPropagation()
                    openEditor(trendline)
                  }}
                />
              )}
              {isSelected && item.anchor2X != null && item.anchor2Y != null && (
                <button
                  type="button"
                  tabIndex={-1}
                  className={`absolute h-4 w-4 cursor-grab rounded-full border-[3px] outline-none active:cursor-grabbing ${
                    isDrawingMode ? 'pointer-events-none' : 'pointer-events-auto'
                  }`}
                  style={{
                    left: `${Number(item.anchor2X) - 8}px`,
                    top: `${Number(item.anchor2Y) - 8}px`,
                    borderColor: color,
                    backgroundColor: '#f8fafc',
                  }}
                  aria-label="추세선 끝점 이동"
                  onPointerDown={(event) => handlePointerDown(event, trendline, 'anchor2')}
                  onDoubleClick={(event) => {
                    event.stopPropagation()
                    openEditor(trendline)
                  }}
                />
              )}
            </div>
          )
        })}

        {isDrawingMode && (
          <div
            className="pointer-events-auto absolute inset-0 cursor-crosshair"
            aria-label="추세선 그리기 영역"
            onPointerMove={handleDrawingPointerMove}
            onPointerLeave={handleDrawingPointerLeave}
            onClick={handleDrawingClick}
          />
        )}
      </div>

      {editing && editDraft && (
        <div className="fixed inset-0 z-50 flex items-center justify-end bg-black/40">
          <div className="flex h-full w-full max-w-md flex-col bg-white text-gray-950 shadow-2xl">
            <div className="flex items-center justify-between px-8 py-7">
              <div className="flex items-center gap-3">
                <h2 className="text-3xl font-bold tracking-normal">추세선</h2>
                <button
                  type="button"
                  className="rounded p-1 text-gray-700 hover:bg-gray-100"
                  aria-label="추세선 이름 편집"
                >
                  ✎
                </button>
              </div>
              <button
                type="button"
                onClick={() => setEditing(null)}
                className="rounded p-2 text-4xl leading-none text-gray-950 hover:bg-gray-100"
                aria-label="추세선 설정 닫기"
              >
                ×
              </button>
            </div>

            <div className="px-8">
              <div className="flex gap-7 border-b border-gray-200 text-2xl font-bold">
                {[
                  ['appearance', '모습'],
                  ['coordinates', '좌표'],
                  ['visibility', '보임'],
                ].map(([id, label]) => (
                  <button
                    key={id}
                    type="button"
                    onClick={() => setActiveTab(id as typeof activeTab)}
                    className={`border-b-4 pb-4 ${
                      activeTab === id
                        ? 'border-gray-950 text-gray-950'
                        : 'border-transparent text-gray-500'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex-1 space-y-8 overflow-y-auto px-8 py-8 text-xl">
              {activeTab === 'appearance' && (
                <>
                  <div className="grid grid-cols-[7rem_1fr] items-center gap-4">
                    <span>라인</span>
                    <div className="flex items-center gap-3">
                      {COLOR_SWATCHES.map((color) => (
                        <button
                          key={color}
                          type="button"
                          onClick={() => setEditDraft((prev) => prev ? { ...prev, color } : prev)}
                          className={`h-10 w-10 rounded-md border ${
                            (editDraft.color ?? defaultColor(editDraft.type)) === color
                              ? 'border-blue-600 ring-2 ring-blue-600'
                              : 'border-gray-300'
                          }`}
                          style={{ backgroundColor: color }}
                          aria-label={`색상 ${color}`}
                        />
                      ))}
                    </div>
                  </div>

                  <div className="grid grid-cols-[7rem_1fr] items-center gap-4">
                    <span>확장</span>
                    <select
                      value={extensionModeOf(editDraft)}
                      onChange={(event) => {
                        const flags = extensionFlags(event.target.value as ExtensionMode)
                        setEditDraft((prev) => prev ? { ...prev, ...flags } : prev)
                      }}
                      className="w-full rounded-lg border-2 border-blue-600 px-4 py-3 text-xl"
                    >
                      {(['none', 'right', 'left', 'both'] as const).map((mode) => (
                        <option key={mode} value={mode}>
                          {extensionLabel(mode)}
                        </option>
                      ))}
                    </select>
                  </div>
                </>
              )}

              {activeTab === 'coordinates' && (
                <div className="space-y-5 text-base">
                  <div>
                    <div className="mb-1 font-semibold">포인트 1</div>
                    <div className="rounded-lg border border-gray-200 px-4 py-3">
                      {editDraft.anchor1Ts}
                      <br />
                      {Number(editDraft.anchor1Price).toLocaleString('ko-KR')}
                    </div>
                  </div>
                  <div>
                    <div className="mb-1 font-semibold">포인트 2</div>
                    <div className="rounded-lg border border-gray-200 px-4 py-3">
                      {editDraft.anchor2Ts}
                      <br />
                      {Number(editDraft.anchor2Price).toLocaleString('ko-KR')}
                    </div>
                  </div>
                </div>
              )}

              {activeTab === 'visibility' && (
                <div className="space-y-4 text-lg">
                  <label className="flex items-center gap-3">
                    <input
                      type="checkbox"
                      checked={editDraft.extendLeft}
                      onChange={(event) =>
                        setEditDraft((prev) => prev ? { ...prev, extendLeft: event.target.checked } : prev)
                      }
                      className="h-6 w-6"
                    />
                    왼쪽 확장
                  </label>
                  <label className="flex items-center gap-3">
                    <input
                      type="checkbox"
                      checked={editDraft.extendRight}
                      onChange={(event) =>
                        setEditDraft((prev) => prev ? { ...prev, extendRight: event.target.checked } : prev)
                      }
                      className="h-6 w-6"
                    />
                    오른쪽 확장
                  </label>
                </div>
              )}
            </div>

            <div className="flex items-center justify-end gap-4 border-t border-gray-200 px-8 py-6">
              <button
                type="button"
                onClick={() => setEditing(null)}
                className="rounded-lg border-2 border-gray-950 px-6 py-3 text-xl font-semibold"
              >
                취소
              </button>
              <button
                type="button"
                onClick={commitEditor}
                className="rounded-lg bg-gray-950 px-6 py-3 text-xl font-semibold text-white"
              >
                확인
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
