import type { BusinessDay, UTCTimestamp } from 'lightweight-charts'
import {
  formatKstAxisTick,
  formatKstCrosshairTime,
  type ChartTimeInput,
} from '../../../components/chart/chartTimeLabels'

type ChartTime = UTCTimestamp | BusinessDay | string

export function toBusinessDay(date: string): BusinessDay {
  const [year, month, day] = date.split('-').map(Number)
  return { year, month, day }
}

export function formatPerformanceChartDate(time: ChartTime): string {
  return formatKstAxisTick(time as ChartTimeInput, '1d', 120)
}

export function formatPerformanceCrosshairDate(time: ChartTime): string {
  return formatKstCrosshairTime(time as ChartTimeInput, '1d')
}
