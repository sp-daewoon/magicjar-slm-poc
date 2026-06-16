import { TickMarkType, type UTCTimestamp } from 'lightweight-charts'
import type { CandleInterval } from '../../api/symbol'

export type ChartTimeInput = UTCTimestamp | { year: number; month: number; day: number } | string
export type AxisBoundaryTimeSet = ReadonlySet<number>
export interface AxisBoundaryTimes {
  day: AxisBoundaryTimeSet
  month: AxisBoundaryTimeSet
  year: AxisBoundaryTimeSet
}

const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const INTRADAY_INTERVAL_MINUTES: Partial<Record<CandleInterval, number>> = {
  '1m': 1,
  '3m': 3,
  '5m': 5,
  '15m': 15,
  '30m': 30,
  '90m': 90,
  '1h': 60,
  '4h': 240,
  '12h': 720,
}
const CHART_INTERVALS: ReadonlySet<string> = new Set([
  '1m',
  '3m',
  '5m',
  '15m',
  '30m',
  '90m',
  '1h',
  '4h',
  '12h',
  '1d',
  '1wk',
  '1mo',
  '1y',
])
const AXIS_STEP_CANDIDATES = [1, 3, 5, 15, 30, 60, 90, 120, 180, 240, 360, 720, 1440]
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']
const HOURLY_CLOSE_VIEW_MAX_BARS = 80
const HOURLY_DAY_VIEW_MAX_BARS = 600
const HOURLY_WEEK_VIEW_MAX_BARS = 2400
const HOURLY_FUTURE_BUSINESS_DAYS = 5
const HOURLY_FUTURE_MAX_POINTS = 80
const HOURLY_FALLBACK_SESSION_HOURS = [9, 10, 11, 12, 13, 14, 15]
const DAY_MS = 24 * 60 * 60 * 1000

interface KstDateParts {
  year: number
  month: number
  day: number
  hour: number
  minute: number
  weekday: number
}

export function isIntradayInterval(interval?: CandleInterval): boolean {
  return interval != null && INTRADAY_INTERVAL_MINUTES[interval] != null
}

export function normalizeChartInterval(interval?: string | CandleInterval | null): CandleInterval | undefined {
  if (!interval) return undefined
  return CHART_INTERVALS.has(interval) ? (interval as CandleInterval) : undefined
}

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

function lwcTimeToMs(time: ChartTimeInput): number {
  if (typeof time === 'number') return time * 1000
  if (typeof time === 'string') return new Date(time).getTime()
  return Date.UTC(time.year, time.month - 1, time.day)
}

function lwcTimeToSeconds(time: ChartTimeInput): number | null {
  const ms = lwcTimeToMs(time)
  if (!Number.isFinite(ms)) return null
  return Math.floor(ms / 1000)
}

function kstWallClockToUtcSeconds(year: number, month: number, day: number, hour: number, minute = 0): UTCTimestamp {
  return Math.floor((Date.UTC(year, month - 1, day, hour, minute) - KST_OFFSET_MS) / 1000) as UTCTimestamp
}

function toKstParts(time: ChartTimeInput): KstDateParts | null {
  const ms = lwcTimeToMs(time)
  if (!Number.isFinite(ms)) return null

  const shifted = new Date(ms + KST_OFFSET_MS)
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    weekday: shifted.getUTCDay(),
  }
}

function chooseIntradayStepMinutes(interval: CandleInterval | undefined, visibleBars: number): number {
  const base = INTRADAY_INTERVAL_MINUTES[interval ?? '1m'] ?? 1
  const target =
    visibleBars <= 80
      ? base
      : visibleBars <= 200
        ? 5
        : visibleBars <= 500
          ? 15
          : visibleBars <= 1000
            ? 30
            : visibleBars <= 2000
              ? 60
              : visibleBars <= 4000
                ? 120
                : 240

  return AXIS_STEP_CANDIDATES.find((step) => step >= target && step >= base && step % base === 0) ?? base
}

function isDateBoundary(parts: KstDateParts, visibleBars: number): boolean {
  if (visibleBars <= 120) return false
  const isMidnight = parts.hour === 0 && parts.minute === 0
  const isKrxOpen = parts.hour === 9 && parts.minute === 0
  return isMidnight || isKrxOpen
}

function formatDateBoundary(parts: KstDateParts): string {
  if (parts.month === 1 && parts.day === 1) return String(parts.year)
  if (parts.day === 1) return `${parts.month}월`
  return String(parts.day)
}

function isHourlyDayBoundary(parts: KstDateParts): boolean {
  if (parts.minute !== 0) return false
  return parts.hour === 0 || parts.hour === 8 || parts.hour === 9
}

function isHourlyRegularHour(parts: KstDateParts): boolean {
  if (parts.minute !== 0) return false
  return parts.hour >= 10 && parts.hour <= 15
}

function formatHourlyMonthBoundary(parts: KstDateParts): string {
  if (parts.month === 1 && parts.day <= 7) return String(parts.year)
  if (parts.day <= 7) return `${parts.month}월`
  return ''
}

function formatHourlyAxisTick(
  parts: KstDateParts,
  visibleBars: number,
  tickMarkType?: TickMarkType,
): string {
  if (tickMarkType === TickMarkType.Year) return String(parts.year)
  if (tickMarkType === TickMarkType.Month) return `${parts.month}월`
  if (tickMarkType === TickMarkType.DayOfMonth) return formatDateBoundary(parts)

  const dayBoundary = isHourlyDayBoundary(parts)

  if (visibleBars <= HOURLY_CLOSE_VIEW_MAX_BARS) {
    if (dayBoundary) return formatDateBoundary(parts)
    return isHourlyRegularHour(parts) ? `${parts.hour}:00` : ''
  }

  if (!dayBoundary) return ''

  if (visibleBars <= HOURLY_DAY_VIEW_MAX_BARS) {
    return formatDateBoundary(parts)
  }

  if (visibleBars <= HOURLY_WEEK_VIEW_MAX_BARS) {
    return formatHourlyMonthBoundary(parts) || String(parts.day)
  }

  return formatHourlyMonthBoundary(parts)
}

export function buildAxisBoundaryTimes(times: readonly ChartTimeInput[]): AxisBoundaryTimes {
  const day = new Set<number>()
  const month = new Set<number>()
  const year = new Set<number>()
  let previousDayKey: string | null = null
  let previousMonthKey: string | null = null
  let previousYear: number | null = null

  for (const time of times) {
    const parts = toKstParts(time)
    const seconds = lwcTimeToSeconds(time)
    if (!parts || seconds == null) continue

    const dayKey = `${parts.year}-${parts.month}-${parts.day}`
    const monthKey = `${parts.year}-${parts.month}`
    if (previousYear !== null && parts.year !== previousYear) {
      year.add(seconds)
    }
    if (previousMonthKey !== null && monthKey !== previousMonthKey) {
      month.add(seconds)
    }
    if (previousDayKey !== null && dayKey !== previousDayKey) {
      day.add(seconds)
    }

    previousYear = parts.year
    previousDayKey = dayKey
    previousMonthKey = monthKey
  }

  return { day, month, year }
}

function inferHourlySessionHours(times: readonly ChartTimeInput[]): number[] {
  const hours = new Map<number, number>()
  for (const time of times.slice(-300)) {
    const parts = toKstParts(time)
    if (!parts || parts.minute !== 0) continue
    hours.set(parts.hour, (hours.get(parts.hour) ?? 0) + 1)
  }

  const inferred = Array.from(hours.entries())
    .filter(([, count]) => count >= 2)
    .map(([hour]) => hour)
    .sort((a, b) => a - b)

  return inferred.length >= 2 ? inferred : HOURLY_FALLBACK_SESSION_HOURS
}

export function buildHourlyFutureWhitespaceTimes(times: readonly ChartTimeInput[]): UTCTimestamp[] {
  const lastTime = times[times.length - 1]
  const lastParts = lastTime ? toKstParts(lastTime) : null
  const lastSeconds = lastTime ? lwcTimeToSeconds(lastTime) : null
  if (!lastParts || lastSeconds == null) return []

  const existing = new Set<number>()
  for (const time of times) {
    const seconds = lwcTimeToSeconds(time)
    if (seconds != null) existing.add(seconds)
  }

  const sessionHours = inferHourlySessionHours(times)
  const future: UTCTimestamp[] = []
  let businessDays = 0
  let dayStartKstMs = Date.UTC(lastParts.year, lastParts.month - 1, lastParts.day)

  while (businessDays < HOURLY_FUTURE_BUSINESS_DAYS && future.length < HOURLY_FUTURE_MAX_POINTS) {
    const cursor = new Date(dayStartKstMs)
    const weekday = cursor.getUTCDay()
    if (weekday !== 0 && weekday !== 6) {
      let addedThisDay = false
      const year = cursor.getUTCFullYear()
      const month = cursor.getUTCMonth() + 1
      const day = cursor.getUTCDate()

      for (const hour of sessionHours) {
        const seconds = kstWallClockToUtcSeconds(year, month, day, hour)
        if (seconds <= lastSeconds || existing.has(seconds)) continue
        future.push(seconds)
        addedThisDay = true
        if (future.length >= HOURLY_FUTURE_MAX_POINTS) break
      }

      if (addedThisDay || dayStartKstMs > Date.UTC(lastParts.year, lastParts.month - 1, lastParts.day)) {
        businessDays += 1
      }
    }

    dayStartKstMs += DAY_MS
  }

  return future
}

export function formatKstAxisTick(
  time: ChartTimeInput,
  interval: CandleInterval | undefined,
  visibleBars: number,
  tickMarkType?: TickMarkType,
  monthBoundaryTimes?: AxisBoundaryTimeSet,
  yearBoundaryTimes?: AxisBoundaryTimeSet,
  dayBoundaryTimes?: AxisBoundaryTimeSet,
): string {
  const parts = toKstParts(time)
  if (!parts) return ''
  const timeSeconds = lwcTimeToSeconds(time)
  const isDataYearBoundary = timeSeconds != null && yearBoundaryTimes?.has(timeSeconds)
  const isDataMonthBoundary = timeSeconds != null && monthBoundaryTimes?.has(timeSeconds)
  const isDataDayBoundary = timeSeconds != null && dayBoundaryTimes?.has(timeSeconds)

  if (isIntradayInterval(interval)) {
    if (tickMarkType === TickMarkType.Year || isDataYearBoundary) return String(parts.year)
    if (tickMarkType === TickMarkType.Month || isDataMonthBoundary) return `${parts.month}월`
    if (tickMarkType === TickMarkType.DayOfMonth || isDataDayBoundary) return formatDateBoundary(parts)

    if (interval === '1h') return formatHourlyAxisTick(parts, visibleBars, tickMarkType)

    if (isDateBoundary(parts, visibleBars)) return formatDateBoundary(parts)

    const step = chooseIntradayStepMinutes(interval, visibleBars)
    const minuteOfDay = parts.hour * 60 + parts.minute
    if (minuteOfDay % step !== 0) return ''
    return `${pad2(parts.hour)}:${pad2(parts.minute)}`
  }

  if (tickMarkType === TickMarkType.Year || isDataYearBoundary || (parts.month === 1 && parts.day === 1)) {
    return String(parts.year)
  }
  if (tickMarkType === TickMarkType.Month || isDataMonthBoundary || parts.day === 1) return `${parts.month}월`
  if (interval === '1mo') return `${parts.month}월`
  if (interval === '1y') return String(parts.year)
  return `${parts.month}/${parts.day}`
}

export function formatKstCrosshairTime(time: ChartTimeInput, interval: CandleInterval | undefined): string {
  const parts = toKstParts(time)
  if (!parts) return ''

  const date = `${parts.year}-${pad2(parts.month)}-${pad2(parts.day)}`
  if (!isIntradayInterval(interval)) {
    return `${WEEKDAYS[parts.weekday]} ${date}`
  }
  return `${WEEKDAYS[parts.weekday]} ${date} ${pad2(parts.hour)}:${pad2(parts.minute)}`
}
