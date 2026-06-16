/**
 * LightweightChart — LWC v4 래퍼 컴포넌트
 *
 * ADR 0025 §2-1 LOCK: LWC v4 공식 API 한정.
 * TradingView Advanced 라이브러리 import 0건.
 * 사용 API: createChart · addCandlestickSeries · addHistogramSeries · setData · update
 *
 * spec §4-4 LWC v4 시그니처 정합:
 * - time: UTCTimestamp (Math.floor(new Date(c.ts).getTime() / 1000))
 * - 거래량 = addHistogramSeries (priceScaleId: '')
 * - STOMP 실시간 갱신: candleSeries.update()
 *
 * 결함 수정 (v3.1.0 dogfooding):
 *   결함 1: ts ISO→UTCTimestamp 변환 + OHLC string→number (기존 로직 유지 확인)
 *   결함 5: KST 시간 포맷 (tickMarkFormatter + Asia/Seoul)
 *   결함 6: hover crosshair OHLCV·등락률 툴팁 (floating div)
 *
 * hotfix: chart-realtime-tick-update
 *   STOMP tick(price·ts) → interval boundary 계산 → 마지막 bar close·high·low 실시간 update
 *   liveTick prop 추가 — SymbolDetailPage → SymbolChart → LightweightChart 전달
 *
 * T-IND PR-33 R3 확장 (W2):
 *   onChartReady?: (chart: IChartApi) => void  — IndicatorOverlay 연동용 chart ref 노출
 *   onChartDestroy?: () => void                — chart destroy 시 알림
 *
 * T-PAT PR-40 R3 확장 (W2):
 *   onCandleSeriesReady?: (series: ISeriesApi<'Candlestick'>) => void — PatternMarker 연동용 series ref 노출
 *   onCandleSeriesDestroy?: () => void                               — series destroy 시 알림
 *
 * Phase 5 chart-curator (ADR 0093 §3-1·§3-2 정합 — 2026-05-20):
 *   maxCandles?: number             — lazy backfill 누적 cap (timeframe별 매트릭스 — candleCountMaxByInterval)
 *   onMaxCapReached?: () => void   — 가드 3 trigger 시 1회 콜백 (toast 1회 표시용 — SymbolChart 책임)
 *
 *   가드 3 의도: 사용자가 좌측 끝까지 스크롤 → candles.length가 cap에 도달했음 신호.
 *   onScrollLeft 호출 차단 + onMaxCapReached 1회 호출. 무한 lazy backfill·과도한 KIS 호출 차단.
 *
 * props:
 *   candles: CandleDto[]           초기 데이터 (ASC 정렬 보장 — backend 정합)
 *   interval: CandleInterval       현재 인터벌 (시간 포맷 분기용)
 *   realtimeCandle?: CandleDto     STOMP push 실시간 candle (optional)
 *   liveTick?: { price: string; ts: string } | null  STOMP tick — 마지막 bar 실시간 update
 *   onScrollLeft?: () => void      좌측 스크롤 끝 콜백 (페이지네이션 트리거)
 *   maxCandles?: number            가드 3 cap — candles.length >= cap이면 onScrollLeft 차단
 *   onMaxCapReached?: () => void   가드 3 trigger 시 1회 콜백 (SymbolChart에서 toast)
 *   onChartReady?: (chart) => void 차트 생성 완료 콜백 (IndicatorOverlay 연동)
 *   onChartDestroy?: () => void    차트 파괴 콜백 (IndicatorOverlay 정리)
 *   onCandleSeriesReady?: (series) => void  캔들 시리즈 노출 (PatternMarker 연동)
 *   onCandleSeriesDestroy?: () => void      캔들 시리즈 파괴 알림
 */
import {
  createChart,
  type CandlestickData,
  type HistogramData,
  type IChartApi,
  type IPriceLine,
  type ISeriesApi,
  type LineData,
  LineStyle,
  type SeriesType,
  type TickMarkType,
  type UTCTimestamp,
  type WhitespaceData,
  type MouseEventParams,
} from 'lightweight-charts'
import { useEffect, useRef, useState, useCallback } from 'react'
import type { CandleDto, CandleInterval } from '../../api/symbol'
import type { RiskPlanLevels } from '../../components/strategy/riskPlanDisplay'
import {
  buildAxisBoundaryTimes,
  buildHourlyFutureWhitespaceTimes,
  type AxisBoundaryTimes,
  formatKstAxisTick,
  formatKstCrosshairTime,
  isIntradayInterval,
} from '../../components/chart/chartTimeLabels'

/** STOMP tick에서 추출한 실시간 가격·시각 */
export interface LiveTick {
  price: string
  priceChange?: string | null
  changeRate?: string | null
  changeSign?: string | null
  ts: string
}

export interface ChartPriceTimePoint {
  ts: string
  price: string
}

interface LightweightChartProps {
  candles: CandleDto[]
  interval?: CandleInterval
  realtimeCandle?: CandleDto | null
  /**
   * STOMP tick — 마지막 bar close·high·low 실시간 update (자본 보호 critical sync hotfix)
   * SymbolDetailPage가 /topic/market.tick.{code} 구독 후 price·ts 전달
   */
  liveTick?: LiveTick | null
  riskPlanLevels?: RiskPlanLevels | null
  onScrollLeft?: () => void
  /**
   * 가드 3 cap — candles.length >= maxCandles 이면 onScrollLeft 호출 차단 (ADR 0093 §3-2).
   * undefined 또는 0 이하 값이면 가드 3 비활성 (기존 동작 유지 — 보수적 fallback).
   */
  maxCandles?: number
  /**
   * 가드 3 trigger 시 1회 콜백 — SymbolChart가 toast 1회 표시 책임 (ADR 0093 §3-2).
   * candles 배열 reference 동일 + cap 도달 상태가 유지되는 동안 중복 호출 차단.
   */
  onMaxCapReached?: () => void
  /** 차트 API 인스턴스를 부모에게 노출 (IndicatorOverlay 연동) */
  onChartReady?: (chart: IChartApi) => void
  /** 차트 파괴 시 알림 */
  onChartDestroy?: () => void
  /** 캔들스틱 시리즈를 부모에게 노출 (PatternMarker 연동 — T-PAT PR-40) */
  onCandleSeriesReady?: (series: ISeriesApi<'Candlestick'>) => void
  /** 캔들스틱 시리즈 파괴 시 알림 */
  onCandleSeriesDestroy?: () => void
  /** 차트 클릭 좌표를 price/time으로 변환해 부모에게 전달 (추세선 수동 입력용) */
  onPriceTimeClick?: (point: ChartPriceTimePoint) => void
  /** 차트 hover 좌표를 price/time으로 변환해 부모에게 전달 (추세선 미리보기용) */
  onPriceTimeMove?: (point: ChartPriceTimePoint | null) => void
}

/**
 * 인터벌이 분봉(intraday)인지 여부.
 * KRX 정규장 09:00~15:30 KST 기준 TradingView식 경계로 마지막 bar를 갱신한다.
 */
const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const MINUTE_MS = 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000
const KRX_REGULAR_OPEN_MIN = 9 * 60
const KRX_REGULAR_CLOSE_MIN = 15 * 60 + 30
const NINETY_MIN_BOUNDARY_STARTS = [9 * 60, 10 * 60 + 30, 12 * 60, 13 * 60 + 30, 15 * 60]

function kstParts(isoString: string): { kstMs: number; dayStartMs: number; minOfDay: number } {
  const kstMs = new Date(isoString).getTime() + KST_OFFSET_MS
  const dayStartMs = Math.floor(kstMs / DAY_MS) * DAY_MS
  const minOfDay = Math.floor((kstMs - dayStartMs) / MINUTE_MS)
  return { kstMs, dayStartMs, minOfDay }
}

function isKrxRegularSessionMinute(isoString: string): boolean {
  const { minOfDay } = kstParts(isoString)
  return minOfDay >= KRX_REGULAR_OPEN_MIN && minOfDay < KRX_REGULAR_CLOSE_MIN
}

function isHigherTimeframeInterval(interval: CandleInterval | undefined): boolean {
  return interval === '1d' || interval === '1wk' || interval === '1mo' || interval === '1y'
}

function anchoredKstBoundaryMs(dayStartMs: number, minOfDay: number, intervalMin: number): number {
  const boundedMin = Math.max(minOfDay, KRX_REGULAR_OPEN_MIN)
  const boundaryMin =
    KRX_REGULAR_OPEN_MIN +
    Math.floor((boundedMin - KRX_REGULAR_OPEN_MIN) / intervalMin) * intervalMin
  return dayStartMs + boundaryMin * MINUTE_MS
}

function ninetyMinKstBoundaryMs(dayStartMs: number, minOfDay: number): number {
  let picked = NINETY_MIN_BOUNDARY_STARTS[0]
  for (const anchor of NINETY_MIN_BOUNDARY_STARTS) {
    if (anchor <= minOfDay) picked = anchor
    else break
  }
  return dayStartMs + picked * MINUTE_MS
}

/**
 * tick 도착 시각(isoString)을 기준으로 interval의 bar time boundary를 UTCTimestamp로 반환.
 * KST(Asia/Seoul, UTC+9)를 기준으로 boundary를 계산하고 UTC로 변환.
 *
 * LWC v4 time은 UTCTimestamp(초) 사용 — bar의 time과 동일한 boundary를 사용해야
 * series.update()가 새 bar 생성 없이 기존 마지막 bar를 덮어쓴다.
 */
function barBoundaryTimestamp(isoString: string, interval: CandleInterval | undefined): UTCTimestamp {
  const { kstMs, dayStartMs, minOfDay } = kstParts(isoString)

  let boundaryKstMs: number

  switch (interval) {
    case '1m': {
      // 현재 분 floor (초·밀리초 제거)
      boundaryKstMs = Math.floor(kstMs / (60 * 1000)) * (60 * 1000)
      break
    }
    case '3m': {
      boundaryKstMs = Math.floor(kstMs / (3 * 60 * 1000)) * (3 * 60 * 1000)
      break
    }
    case '5m': {
      boundaryKstMs = Math.floor(kstMs / (5 * 60 * 1000)) * (5 * 60 * 1000)
      break
    }
    case '15m': {
      boundaryKstMs = Math.floor(kstMs / (15 * 60 * 1000)) * (15 * 60 * 1000)
      break
    }
    case '30m': {
      boundaryKstMs = Math.floor(kstMs / (30 * 60 * 1000)) * (30 * 60 * 1000)
      break
    }
    case '90m': {
      boundaryKstMs = ninetyMinKstBoundaryMs(dayStartMs, minOfDay)
      break
    }
    case '1h': {
      boundaryKstMs = Math.floor(kstMs / (60 * 60 * 1000)) * (60 * 60 * 1000)
      break
    }
    case '4h': {
      boundaryKstMs = anchoredKstBoundaryMs(dayStartMs, minOfDay, 4 * 60)
      break
    }
    case '12h': {
      boundaryKstMs = dayStartMs + KRX_REGULAR_OPEN_MIN * MINUTE_MS
      break
    }
    case '1wk': {
      // 이번 주 월요일 KST 00:00
      // KST Date 생성 후 요일 추출
      const kstDate = new Date(kstMs)
      // getUTCDay()로 요일 계산 (kstMs는 이미 KST shifted)
      const dayOfWeek = kstDate.getUTCDay() // 0=일, 1=월 ... 6=토
      const daysSinceMonday = dayOfWeek === 0 ? 6 : dayOfWeek - 1
      boundaryKstMs = dayStartMs - daysSinceMonday * DAY_MS
      break
    }
    case '1mo': {
      // 이번 달 1일 KST 00:00
      const kstDate = new Date(kstMs)
      const year = kstDate.getUTCFullYear()
      const month = kstDate.getUTCMonth()
      boundaryKstMs = Date.UTC(year, month, 1)
      break
    }
    case '1y': {
      // 올해 1월 1일 KST 00:00
      const kstDate = new Date(kstMs)
      const year = kstDate.getUTCFullYear()
      boundaryKstMs = Date.UTC(year, 0, 1)
      break
    }
    case '1d':
    default: {
      // 오늘 KST 00:00
      boundaryKstMs = dayStartMs
      break
    }
  }

  // KST boundary → UTC seconds
  const boundaryUtcMs = boundaryKstMs - KST_OFFSET_MS
  return Math.floor(boundaryUtcMs / 1000) as UTCTimestamp
}

function toUTCTimestamp(isoString: string): UTCTimestamp {
  return Math.floor(new Date(isoString).getTime() / 1000) as UTCTimestamp
}

function toCandlestickData(c: CandleDto): CandlestickData {
  return {
    time: toUTCTimestamp(c.ts),
    open: parseFloat(c.open),
    high: parseFloat(c.high),
    low: parseFloat(c.low),
    close: parseFloat(c.close),
  }
}

function toHistogramData(c: CandleDto): HistogramData {
  const close = parseFloat(c.close)
  const open = parseFloat(c.open)
  return {
    time: toUTCTimestamp(c.ts),
    value: c.volume,
    color: close >= open ? '#26a69a44' : '#ef535044',
  }
}

function lwcTimeToIso(time: MouseEventParams['time']): string | null {
  if (!time) return null
  const ms =
    typeof time === 'number'
      ? time * 1000
      : typeof time === 'string'
        ? new Date(time).getTime()
      : Date.UTC(time.year, time.month - 1, time.day)
  if (!Number.isFinite(ms)) return null
  return new Date(ms).toISOString()
}

/** 가격 천 단위 콤마 포맷 */
function fmtPrice(v: number): string {
  return v.toLocaleString('ko-KR')
}

const KRW_PRICE_FORMAT = {
  type: 'custom' as const,
  minMove: 1,
  formatter: fmtPrice,
}

/** 거래량 포맷 */
function fmtVolume(v: number): string {
  if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}백만`
  if (v >= 1_000) return `${(v / 1_000).toFixed(0)}천`
  return v.toLocaleString('ko-KR')
}

function signedChangeRate(value: string, changeSign?: string | null): string {
  const num = parseFloat(value)
  if (isNaN(num)) return value
  if (changeSign === '1' || changeSign === '2') return Math.abs(num).toFixed(2)
  if (changeSign === '3') return '0.00'
  if (changeSign === '4' || changeSign === '5') return (-Math.abs(num)).toFixed(2)
  return num.toFixed(2)
}

interface TooltipState {
  visible: boolean
  x: number
  y: number
  open: number
  high: number
  low: number
  close: number
  volume: number
  changeRate: string
  timeLabel: string
}

export function LightweightChart({
  candles,
  interval,
  realtimeCandle,
  liveTick,
  riskPlanLevels,
  onScrollLeft,
  maxCandles,
  onMaxCapReached,
  onChartReady,
  onChartDestroy,
  onCandleSeriesReady,
  onCandleSeriesDestroy,
  onPriceTimeClick,
  onPriceTimeMove,
}: LightweightChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<SeriesType> | null>(null)
  const timeAxisSeriesRef = useRef<ISeriesApi<'Line'> | null>(null)
  const riskPlanPriceLinesRef = useRef<IPriceLine[]>([])
  const riskPlanLineSeriesRef = useRef<ISeriesApi<'Line'>[]>([])
  const onScrollLeftRef = useRef(onScrollLeft)
  onScrollLeftRef.current = onScrollLeft
  const onChartReadyRef = useRef(onChartReady)
  onChartReadyRef.current = onChartReady
  const onChartDestroyRef = useRef(onChartDestroy)
  onChartDestroyRef.current = onChartDestroy
  const onCandleSeriesReadyRef = useRef(onCandleSeriesReady)
  onCandleSeriesReadyRef.current = onCandleSeriesReady
  const onCandleSeriesDestroyRef = useRef(onCandleSeriesDestroy)
  onCandleSeriesDestroyRef.current = onCandleSeriesDestroy
  const onPriceTimeClickRef = useRef(onPriceTimeClick)
  onPriceTimeClickRef.current = onPriceTimeClick
  const onPriceTimeMoveRef = useRef(onPriceTimeMove)
  onPriceTimeMoveRef.current = onPriceTimeMove

  // Phase 5 ADR 0093 §3-2 가드 3 — 최신 candles.length·maxCandles·onMaxCapReached ref
  // (subscribeVisibleLogicalRangeChange 콜백 클로저에서 최신 값 참조 — useEffect 의존성 없이 안전)
  const candlesLengthRef = useRef<number>(candles.length)
  candlesLengthRef.current = candles.length
  const maxCandlesRef = useRef<number | undefined>(maxCandles)
  maxCandlesRef.current = maxCandles
  const onMaxCapReachedRef = useRef(onMaxCapReached)
  onMaxCapReachedRef.current = onMaxCapReached

  // interval ref — tickMarkFormatter 클로저에서 최신 값 참조
  const intervalRef = useRef(interval)
  intervalRef.current = interval
  // 현재 화면에 노출된 logical bar 수 — 하단 시간축 라벨 밀도 계산용
  const visibleBarCountRef = useRef(Math.max(1, Math.min(candles.length, 200)))
  const axisBoundaryTimesRef = useRef<AxisBoundaryTimes>({ day: new Set(), month: new Set(), year: new Set() })

  // 툴팁 상태
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    x: 0,
    y: 0,
    open: 0,
    high: 0,
    low: 0,
    close: 0,
    volume: 0,
    changeRate: '0.00',
    timeLabel: '',
  })

  // volume lookup: ts(UTCTimestamp) → volume
  const volumeMapRef = useRef<Map<number, number>>(new Map())
  // changeRate lookup: ts(UTCTimestamp) → 전일종가 기준 등락률
  const changeRateMapRef = useRef<Map<number, string>>(new Map())

  /**
   * 마지막 bar OHLC 추적 ref — liveTick update 시 open·low·high 유지용.
   * candles setData 또는 realtimeCandle update 시 갱신됨.
   */
  const lastBarRef = useRef<{ time: UTCTimestamp; open: number; high: number; low: number; close: number } | null>(null)

  /**
   * 차트 좌측 스크롤 자동 페이지네이션 false trigger·과도한 KIS 호출 차단 — 3단 가드
   *
   * ### 가드 1 — 첫 setData 후 1초 cooldown (backlog §30 fix · 2026-05-11)
   * LWC v4 setData + fitContent 직후 timeScale이 visibleLogicalRange 콜백을 자동 발화.
   * fitContent는 우측 패딩을 두기 위해 range.from 값을 음수로 설정(예: -10 ~ -50)하므로
   * 사용자가 좌측 스크롤하지 않았음에도 onScrollLeft 자동 호출 → fetchNextPage(before=oldest_ts)
   * → 옛 candle 200개 즉시 요청.
   * Dogfooding evidence (2026-05-11): 차트 첫 진입 시 `before=2009-10-31T15:00:00Z` 자동 호출
   *                                 + 응답 `ts=2000-01-31T15:00:00Z` — 의도되지 않은 초기 페이지네이션.
   *
   * ### 가드 2 — range.from 단조감소(좌측 pan) 검증 (backlog §30 fix · 2026-05-11)
   * 직전 range.from보다 더 작아진 경우만 onScrollLeft 호출(사용자가 실제 좌측 pan했음 신호).
   * 우측 zoom·resize 같은 false positive 차단.
   *
   * ### 가드 3 — max cap 도달 시 lazy backfill 차단 + toast 1회 (Phase 5 · ADR 0093 §3-2 · 2026-05-20)
   * candles.length가 maxCandles에 도달하면 onScrollLeft 호출 차단 + onMaxCapReached 1회 콜백.
   * Dogfooding evidence (2026-05-20 한국석유 004090):
   *   - 일봉 600 → 800 → 1000봉 누적 lazy backfill 후 cap(5000) 미도달 → 가드 3 통과
   *   - 일봉 5000봉 도달 시 가드 3 발동 → toast "표시 봉수 최대 도달 (5000봉)" 1회 표시 후 호출 중단
   * 효과: 무한 lazy backfill·KIS Rate Limit 침범·DB 적재량 폭주 차단 (ADR 0086 §14 함정 가드 정합).
   * Idempotency: maxCapReachedRef로 candles 배열이 cap 이하로 줄지 않는 한 콜백 1회만 발화.
   *
   * ★ 페르소나 lens "동작하는 코드를 함부로 건드리지 마라": 가드 1·2는 backlog §30 fix LOCK 그대로,
   * 가드 3은 추가만 (변경 0). 롤백 시 가드 3 분기만 git revert → 가드 1·2 무영향.
   */
  const setDataReceivedAtRef = useRef<number>(0)
  const lastVisibleRangeFromRef = useRef<number | null>(null)
  /** 가드 3 idempotency — cap 도달 toast 1회 보장 (cap 미만으로 줄면 다시 trigger 가능) */
  const maxCapReachedRef = useRef<boolean>(false)

  const hideTooltip = useCallback(() => {
    setTooltip((prev) => ({ ...prev, visible: false }))
  }, [])

  // dispose 플래그 — cleanup 후 async callback에서 stale 참조 방지
  const isDisposedRef = useRef(false)

  const clearRiskPlanPriceLines = useCallback(() => {
    const series = candleSeriesRef.current
    const lines = riskPlanPriceLinesRef.current
    riskPlanPriceLinesRef.current = []
    if (!series || lines.length === 0) return
    lines.forEach((line) => {
      try {
        series.removePriceLine(line)
      } catch {
        // disposed series — graceful cleanup
      }
    })
  }, [])

  const clearRiskPlanLineSeries = useCallback(() => {
    const chart = chartRef.current
    const seriesList = riskPlanLineSeriesRef.current
    riskPlanLineSeriesRef.current = []
    if (!chart || seriesList.length === 0) return
    seriesList.forEach((series) => {
      try {
        chart.removeSeries(series)
      } catch {
        // disposed chart — graceful cleanup
      }
    })
  }, [])

  const clearRiskPlanOverlay = useCallback(() => {
    clearRiskPlanPriceLines()
    clearRiskPlanLineSeries()
  }, [clearRiskPlanLineSeries, clearRiskPlanPriceLines])

  // 차트 생성 (마운트 1회)
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    isDisposedRef.current = false

    const chart = createChart(container, {
      width: container.clientWidth,
      height: container.clientHeight || 400,
      layout: {
        background: { color: '#0d1117' },
        textColor: '#9ca3af',
      },
      grid: {
        vertLines: { color: '#1f2937' },
        horzLines: { color: '#1f2937' },
      },
      crosshair: {
        mode: 1, // CrosshairMode.Magnet
      },
      rightPriceScale: {
        borderColor: '#374151',
      },
      timeScale: {
        borderColor: '#374151',
        timeVisible: true,
        secondsVisible: false,
        tickMarkFormatter: (
          time: UTCTimestamp | { year: number; month: number; day: number },
          tickMarkType: TickMarkType,
        ) => {
          const boundaryTimes = axisBoundaryTimesRef.current
          return formatKstAxisTick(
            time,
            intervalRef.current,
            visibleBarCountRef.current,
            tickMarkType,
            boundaryTimes.month,
            boundaryTimes.year,
            boundaryTimes.day,
          )
        },
      },
      localization: {
        locale: 'ko-KR',
        priceFormatter: fmtPrice,
        timeFormatter: (time: UTCTimestamp | { year: number; month: number; day: number } | string) => {
          return formatKstCrosshairTime(time, intervalRef.current)
        },
        dateFormat: 'yyyy-MM-dd',
      },
    })
    chartRef.current = chart

    // IndicatorOverlay 연동 콜백
    onChartReadyRef.current?.(chart)

    // 캔들스틱 시리즈
    const candleSeries = chart.addCandlestickSeries({
      upColor: '#26a69a',
      downColor: '#ef5350',
      borderDownColor: '#ef5350',
      borderUpColor: '#26a69a',
      wickDownColor: '#ef5350',
      wickUpColor: '#26a69a',
      priceFormat: KRW_PRICE_FORMAT,
    })
    candleSeriesRef.current = candleSeries
    // PatternMarker 연동 콜백 (T-PAT PR-40)
    onCandleSeriesReadyRef.current?.(candleSeries)

    // 거래량 히스토그램 시리즈 (optional — 가격 스케일과 독립)
    const volumeSeries = chart.addHistogramSeries({
      priceScaleId: '',
      priceFormat: { type: 'volume' },
    })
    volumeSeries.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    volumeSeriesRef.current = volumeSeries

    const timeAxisSeries = chart.addLineSeries({
      visible: false,
      priceLineVisible: false,
      lastValueVisible: false,
      lineVisible: false,
      crosshairMarkerVisible: false,
      priceScaleId: '',
    })
    timeAxisSeriesRef.current = timeAxisSeries

    const handleClick = (param: MouseEventParams) => {
      if (isDisposedRef.current) return
      if (!param.time || !param.point) return
      const callback = onPriceTimeClickRef.current
      if (!callback) return

      const price = candleSeriesRef.current?.coordinateToPrice(param.point.y)
      if (price == null || !Number.isFinite(price)) return
      const ts = lwcTimeToIso(param.time)
      if (!ts) return

      callback({
        ts,
        price: price.toFixed(4),
      })
    }
    chart.subscribeClick(handleClick)

    // 차트 좌측 스크롤 끝 도달 감지 (페이지네이션 트리거 — 3단 가드)
    //
    // 가드 1) setData 후 1초 cooldown — fitContent 자동 발화 무시. (backlog §30 fix)
    // 가드 2) range.from 단조감소 — 좌측 pan 신호일 때만 트리거. (backlog §30 fix)
    //         (이전 from > 현재 from = 좌측 이동, zoom·resize·우측 pan은 무시)
    // 가드 3) candles.length >= maxCandles 시 lazy backfill 차단 + toast 1회. (Phase 5 ADR 0093 §3-2)
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) return
      visibleBarCountRef.current = Math.max(1, Math.ceil(range.to - range.from))
      if (!onScrollLeftRef.current) return

      // 가드 1: setData 직후 1초간은 자동 콜백(fitContent)이므로 무시
      const elapsedMs = Date.now() - setDataReceivedAtRef.current
      if (setDataReceivedAtRef.current === 0 || elapsedMs < 1000) {
        // 초기 from 값만 기록 — 가드 2 비교 기준 확립
        lastVisibleRangeFromRef.current = range.from
        return
      }

      // 가드 2: 이전 from 대비 단조감소(좌측 pan)일 때만 트리거
      const prevFrom = lastVisibleRangeFromRef.current
      lastVisibleRangeFromRef.current = range.from

      if (prevFrom === null || range.from >= prevFrom) {
        // 변화 없음·우측 pan·zoom — 무시
        return
      }

      // 좌측 끝 도달 (range.from < 1: 차트 좌측 경계 근접) + 단조감소(실제 좌측 pan)
      if (range.from < 1) {
        // 가드 3: max cap 도달 검사 (ADR 0093 §3-2 — 무한 lazy backfill·KIS 폭주 차단)
        const cap = maxCandlesRef.current
        const count = candlesLengthRef.current
        if (typeof cap === 'number' && cap > 0 && count >= cap) {
          // 1회만 toast — cap 미만으로 줄지 않는 한 중복 trigger 차단 (idempotent)
          if (!maxCapReachedRef.current) {
            maxCapReachedRef.current = true
            try {
              onMaxCapReachedRef.current?.()
            } catch {
              // toast 측 결함이 차트에 영향 주지 않도록 graceful (보수 페르소나 — 방어적 프로그래밍)
            }
          }
          // lazy backfill 호출 차단
          return
        }
        onScrollLeftRef.current()
      }
    })

    // crosshair 이동 → hover 툴팁 업데이트
    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      // chart가 dispose된 후 실행되는 async callback 방지
      if (isDisposedRef.current) return

      if (
        !param.time ||
        !param.point ||
        param.point.x < 0 ||
        param.point.y < 0
      ) {
        onPriceTimeMoveRef.current?.(null)
        hideTooltip()
        return
      }

      const cs = candleSeriesRef.current
      if (!cs) {
        onPriceTimeMoveRef.current?.(null)
        hideTooltip()
        return
      }

      const hoverPrice = cs.coordinateToPrice(param.point.y)
      const hoverTs = lwcTimeToIso(param.time)
      onPriceTimeMoveRef.current?.(
        hoverPrice != null && Number.isFinite(hoverPrice) && hoverTs
          ? {
              ts: hoverTs,
              price: hoverPrice.toFixed(4),
            }
          : null,
      )

      const candle = param.seriesData.get(cs) as CandlestickData | undefined
      if (!candle) {
        hideTooltip()
        return
      }

      const ts =
        typeof param.time === 'number'
          ? (param.time as UTCTimestamp)
          : (Math.floor(
              Date.UTC(
                (param.time as { year: number; month: number; day: number }).year,
                (param.time as { year: number; month: number; day: number }).month - 1,
                (param.time as { year: number; month: number; day: number }).day,
              ) / 1000,
            ) as UTCTimestamp)

      const volume = volumeMapRef.current.get(ts) ?? 0
      const changeRate =
        changeRateMapRef.current.get(ts) ??
        (candle.open !== 0
          ? (((candle.close - candle.open) / candle.open) * 100).toFixed(2)
          : '0.00')

      const containerRect = container.getBoundingClientRect()
      // param.point는 차트 내부 좌표 — 컨테이너 기준 위치 사용
      const chartEl = container.querySelector('canvas')
      const offsetLeft = chartEl ? chartEl.getBoundingClientRect().left - containerRect.left : 0
      const offsetTop = chartEl ? chartEl.getBoundingClientRect().top - containerRect.top : 0

      setTooltip({
        visible: true,
        x: param.point.x + offsetLeft,
        y: param.point.y + offsetTop,
        open: candle.open,
        high: candle.high,
        low: candle.low,
        close: candle.close,
        volume,
        changeRate,
        timeLabel: formatKstCrosshairTime(param.time, intervalRef.current),
      })
    })

    // 반응형 리사이즈
    const resizeObserver = new ResizeObserver((entries) => {
      if (isDisposedRef.current) return
      for (const entry of entries) {
        try {
          chart.applyOptions({ width: entry.contentRect.width })
        } catch {
          // 이미 dispose된 경우 무시
        }
      }
    })
    resizeObserver.observe(container)

    return () => {
      // dispose 플래그를 먼저 세워 async callback(crosshair/scroll)이 stale 인스턴스에 접근 차단
      isDisposedRef.current = true
      resizeObserver.disconnect()
      // series ref를 먼저 null 처리 — IndicatorOverlay cleanup이 removeSeries 호출 전에
      // chartRef.current가 유효하더라도 이미 dispose 예정임을 알 수 있도록 순서 보장
      clearRiskPlanOverlay()
      candleSeriesRef.current = null
      volumeSeriesRef.current = null
      timeAxisSeriesRef.current = null
      // 콜백 알림 (IndicatorOverlay·PatternMarker가 ref 정리 가능하도록)
      onCandleSeriesDestroyRef.current?.()
      onChartDestroyRef.current?.()
      try {
        chart.unsubscribeClick(handleClick)
      } catch {
        // 이미 dispose된 경우 무시
      }
      // chart.remove() — try/catch: 이미 dispose된 경우 graceful 무시
      try {
        chart.remove()
      } catch {
        // 이미 dispose된 경우 무시
      }
      chartRef.current = null
    }
  }, [clearRiskPlanOverlay, hideTooltip])

  useEffect(() => {
    const series = candleSeriesRef.current
    const chart = chartRef.current
    if (!series || !chart) return

    clearRiskPlanOverlay()
    if (!riskPlanLevels) return

    const firstCandle = candles[0]
    const lastCandle = candles[candles.length - 1]
    const makeLineData = (price: number): LineData[] => {
      if (!firstCandle) return []
      const firstTime = toUTCTimestamp(firstCandle.ts)
      const lastTime = lastCandle ? toUTCTimestamp(lastCandle.ts) : firstTime
      if (firstTime === lastTime) {
        return [{ time: firstTime, value: price }]
      }
      return [
        { time: firstTime, value: price },
        { time: lastTime, value: price },
      ]
    }

    const riskLines = [
      { price: riskPlanLevels.stopPrice, color: '#ef4444', width: 2 },
      { price: riskPlanLevels.entryPrice, color: '#f59e0b', width: 1 },
      { price: riskPlanLevels.takePrice, color: '#22c55e', width: 2 },
    ] as const
    riskPlanLineSeriesRef.current = riskLines
      .map((line) => {
        const lineSeries = chart.addLineSeries({
          color: line.color,
          lineWidth: line.width,
          lineStyle: LineStyle.Dashed,
          priceLineVisible: false,
          lastValueVisible: false,
          priceFormat: KRW_PRICE_FORMAT,
        })
        lineSeries.setData(makeLineData(line.price))
        return lineSeries
      })

    riskPlanPriceLinesRef.current = [
      series.createPriceLine({
        price: riskPlanLevels.stopPrice,
        color: '#ef4444',
        lineWidth: 2,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: `손절 -${riskPlanLevels.stopLossPct.toLocaleString('ko-KR')}%`,
      }),
      series.createPriceLine({
        price: riskPlanLevels.entryPrice,
        color: '#f59e0b',
        lineWidth: 1,
        lineStyle: LineStyle.Solid,
        axisLabelVisible: true,
        title: '진입 기준',
      }),
      series.createPriceLine({
        price: riskPlanLevels.takePrice,
        color: '#22c55e',
        lineWidth: 2,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        title: `익절 +${riskPlanLevels.takeProfitPct.toLocaleString('ko-KR')}%`,
      }),
    ]

    return clearRiskPlanOverlay
  }, [
    candles,
    clearRiskPlanOverlay,
    riskPlanLevels?.entryPrice,
    riskPlanLevels?.stopPrice,
    riskPlanLevels?.takePrice,
    riskPlanLevels?.stopLossPct,
    riskPlanLevels?.takeProfitPct,
  ])

  // interval 변경 시 timeScale 옵션 재적용 (tickMarkFormatter 갱신)
  useEffect(() => {
    if (!chartRef.current) return
    chartRef.current.applyOptions({
      timeScale: {
        timeVisible: isIntradayInterval(interval),
        secondsVisible: false,
      },
    })
  }, [interval])

  // 캔들 데이터 업데이트 (interval 변경 또는 초기 로드)
  useEffect(() => {
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    const timeAxisSeries = timeAxisSeriesRef.current
    if (!candleSeries || !volumeSeries || !timeAxisSeries) return

    if (candles.length === 0) {
      candleSeries.setData([])
      volumeSeries.setData([])
      timeAxisSeries.setData([])
      volumeMapRef.current = new Map()
      changeRateMapRef.current = new Map()
      lastBarRef.current = null
      return
    }

    // backend가 ASC 정렬 보장 (spec §3-6 §10-6 정합)
    const candleData = candles.map(toCandlestickData)
    const volumeData = candles.map(toHistogramData)
    const candleTimes = candleData.map((candle) => candle.time as UTCTimestamp)
    const futureHourlyTimes = interval === '1h' ? buildHourlyFutureWhitespaceTimes(candleTimes) : []
    const futureTimeAxisData = futureHourlyTimes.map((time): WhitespaceData => ({ time }))
    axisBoundaryTimesRef.current = buildAxisBoundaryTimes([...candleTimes, ...futureHourlyTimes])

    // volume lookup 맵 갱신
    const vMap = new Map<number, number>()
    const changeRateMap = new Map<number, string>()
    candles.forEach((c) => {
      const ts = toUTCTimestamp(c.ts)
      vMap.set(ts, c.volume)
      if (c.changeRate != null) {
        changeRateMap.set(ts, c.changeRate)
      }
    })
    volumeMapRef.current = vMap
    changeRateMapRef.current = changeRateMap

    candleSeries.setData(candleData)
    volumeSeries.setData(volumeData)
    timeAxisSeries.setData(futureTimeAxisData)
    chartRef.current?.timeScale().fitContent()

    // backlog §30 fix — setData 시각 기록 (visibleLogicalRange cooldown 가드용)
    setDataReceivedAtRef.current = Date.now()
    // 추가 페이지 append 시 prevFrom 단조감소 비교 baseline 재설정
    lastVisibleRangeFromRef.current = null
    // Phase 5 가드 3 idempotency 재설정 — cap 미만이면 toast 재발화 가능 상태 복원
    // (interval 변경·재진입·종목 전환 시 새 cap·새 candles 모수에 맞춰 가드 3 재무장)
    {
      const cap = maxCandlesRef.current
      if (typeof cap !== 'number' || cap <= 0 || candles.length < cap) {
        maxCapReachedRef.current = false
      }
    }

    // 마지막 bar 추적 — liveTick update 시 open·low·high 유지용
    const last = candleData[candleData.length - 1]
    if (last) {
      lastBarRef.current = {
        time: last.time as UTCTimestamp,
        open: last.open,
        high: last.high,
        low: last.low,
        close: last.close,
      }
    }
  }, [candles])

  // STOMP 실시간 candle 갱신
  useEffect(() => {
    if (!realtimeCandle) return
    const candleSeries = candleSeriesRef.current
    const volumeSeries = volumeSeriesRef.current
    if (!candleSeries || !volumeSeries) return

    try {
      const cd = toCandlestickData(realtimeCandle)
      const vd = toHistogramData(realtimeCandle)
      candleSeries.update(cd)
      volumeSeries.update(vd)
      // volume 맵도 갱신
      volumeMapRef.current.set(cd.time as number, realtimeCandle.volume)
      if (realtimeCandle.changeRate != null) {
        changeRateMapRef.current.set(cd.time as number, realtimeCandle.changeRate)
      }
      // 마지막 bar 추적 갱신
      lastBarRef.current = {
        time: cd.time as UTCTimestamp,
        open: cd.open,
        high: cd.high,
        low: cd.low,
        close: cd.close,
      }
    } catch {
      // LWC update 오류 (잘못된 시간 순서 등) — graceful 무시
    }
  }, [realtimeCandle])

  /**
   * STOMP tick → 마지막 bar 실시간 update (자본 보호 critical sync hotfix)
   *
   * tick.price를 close로 설정, high/low는 기존 값과 비교해 확장.
   * bar time은 interval boundary로 계산 (새 bar 생성 방지).
   * isDisposedRef 가드 + try-catch — lifecycle 안전.
   */
  useEffect(() => {
    if (!liveTick) return
    if (isDisposedRef.current) return

    const candleSeries = candleSeriesRef.current
    if (!candleSeries) return

    const price = parseFloat(liveTick.price)
    if (isNaN(price) || price <= 0) return
    if (isIntradayInterval(intervalRef.current) && !isKrxRegularSessionMinute(liveTick.ts)) return

    try {
      const barTime = barBoundaryTimestamp(liveTick.ts, intervalRef.current)
      const prev = lastBarRef.current
      const updatesExistingBar = prev != null && prev.time === barTime

      // 1d 이상은 공식 당일/기간 OHLCV seed가 들어온 bar만 tick close로 보정한다.
      // 현재가 하나만으로 새 일봉/주봉을 만들면 open/high/low가 모두 현재가가 되어 차트가 오염된다.
      if (!updatesExistingBar && isHigherTimeframeInterval(intervalRef.current)) return

      // intraday는 current bar가 아직 없을 때만 첫 관측 tick으로 임시 bar를 시작한다.
      const open = updatesExistingBar ? prev.open : price
      const high = updatesExistingBar ? Math.max(prev.high, price) : price
      const low  = updatesExistingBar ? Math.min(prev.low,  price) : price

      const updated = {
        time: barTime,
        open,
        high,
        low,
        close: price,
      }
      candleSeries.update(updated)
      if (liveTick.changeRate != null) {
        changeRateMapRef.current.set(barTime, signedChangeRate(liveTick.changeRate, liveTick.changeSign))
      }

      // lastBarRef 갱신
      lastBarRef.current = updated
    } catch {
      // LWC update 오류 — graceful 무시 (dispose 후 stale ref 접근 등)
    }
  }, [liveTick])

  const changeRateNum = parseFloat(tooltip.changeRate)
  const changeRateColor =
    changeRateNum > 0 ? 'text-red-400' : changeRateNum < 0 ? 'text-blue-400' : 'text-gray-300'

  return (
    <div className="relative h-[520px] w-full xl:h-[620px]" aria-label="종목 차트" role="img">
      <div ref={containerRef} className="w-full h-full" />

      {/* hover 툴팁 — 결함 6 */}
      {tooltip.visible && (
        <div
          className="pointer-events-none absolute z-10 rounded-lg border border-gray-700 bg-gray-900/95 p-2 text-xs shadow-xl"
          style={{
            left: Math.min(tooltip.x + 12, (containerRef.current?.clientWidth ?? 300) - 160),
            top: Math.max(tooltip.y - 80, 4),
            minWidth: '148px',
          }}
          role="tooltip"
          aria-label="캔들 상세 정보"
        >
          <div className="mb-1 text-gray-400 text-[10px]">{tooltip.timeLabel}</div>
          <dl className="grid grid-cols-2 gap-x-3 gap-y-0.5">
            <dt className="text-gray-500">시가</dt>
            <dd className="text-right tabular-nums text-gray-200">{fmtPrice(tooltip.open)}</dd>
            <dt className="text-gray-500">고가</dt>
            <dd className="text-right tabular-nums text-red-300">{fmtPrice(tooltip.high)}</dd>
            <dt className="text-gray-500">저가</dt>
            <dd className="text-right tabular-nums text-blue-300">{fmtPrice(tooltip.low)}</dd>
            <dt className="text-gray-500">종가</dt>
            <dd className="text-right tabular-nums text-gray-200">{fmtPrice(tooltip.close)}</dd>
            <dt className="text-gray-500">거래량</dt>
            <dd className="text-right tabular-nums text-gray-300">{fmtVolume(tooltip.volume)}</dd>
            <dt className="text-gray-500">등락률</dt>
            <dd className={`text-right tabular-nums font-semibold ${changeRateColor}`}>
              {changeRateNum >= 0 ? '+' : ''}
              {tooltip.changeRate}%
            </dd>
          </dl>
        </div>
      )}
    </div>
  )
}
