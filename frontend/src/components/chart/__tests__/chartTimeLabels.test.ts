import { describe, expect, it } from 'vitest'
import { TickMarkType } from 'lightweight-charts'
import {
  buildAxisBoundaryTimes,
  buildHourlyFutureWhitespaceTimes,
  formatKstAxisTick,
  formatKstCrosshairTime,
  normalizeChartInterval,
  type ChartTimeInput,
} from '../chartTimeLabels'

function ts(iso: string): ChartTimeInput {
  return Math.floor(new Date(iso).getTime() / 1000) as ChartTimeInput
}

describe('shared chartTimeLabels', () => {
  it('문자열 interval은 지원 interval만 공용 차트 interval로 정규화한다', () => {
    expect(normalizeChartInterval('1m')).toBe('1m')
    expect(normalizeChartInterval('1h')).toBe('1h')
    expect(normalizeChartInterval('not-supported')).toBeUndefined()
  })

  it('crosshair 하단 라벨은 요일과 KST 일시를 표시한다', () => {
    expect(formatKstCrosshairTime(ts('2026-06-05T06:31:00Z'), '1m')).toBe('금 2026-06-05 15:31')
    expect(formatKstCrosshairTime(ts('2026-06-05T06:31:00Z'), '1d')).toBe('금 2026-06-05')
  })

  it('1분봉 축 라벨은 확대 상태에서 분 단위, 넓은 범위에서 30분 경계로 줄인다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T06:31:00Z'), '1m', 40)).toBe('15:31')
    expect(formatKstAxisTick(ts('2026-06-05T06:31:00Z'), '1m', 600)).toBe('')
    expect(formatKstAxisTick(ts('2026-06-05T06:30:00Z'), '1m', 600)).toBe('15:30')
  })

  it('3분봉처럼 base interval이 있는 축은 15분 이상 경계로 정렬한다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T06:09:00Z'), '3m', 180)).toBe('')
    expect(formatKstAxisTick(ts('2026-06-05T06:15:00Z'), '3m', 180)).toBe('15:15')
  })

  it('1시간봉 확대 축은 장 시작은 날짜, 정규장 내부는 시간으로 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T00:00:00Z'), '1h', 40)).toBe('5')
    expect(formatKstAxisTick(ts('2026-06-05T01:00:00Z'), '1h', 40)).toBe('10:00')
    expect(formatKstAxisTick(ts('2026-06-05T06:00:00Z'), '1h', 40)).toBe('15:00')
    expect(formatKstAxisTick(ts('2026-06-05T09:00:00Z'), '1h', 40)).toBe('')
  })

  it('1시간봉 날짜 범위 축은 시간 대신 날짜·월 경계만 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T00:00:00Z'), '1h', 220)).toBe('5')
    expect(formatKstAxisTick(ts('2026-06-05T01:00:00Z'), '1h', 220)).toBe('')
    expect(formatKstAxisTick(ts('2026-05-31T23:00:00Z'), '1h', 220)).toBe('6월')
  })

  it('1시간봉 일자 tick은 장중 시간이어도 날짜를 우선 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T03:00:00Z'), '1h', 80, TickMarkType.DayOfMonth)).toBe('5')
  })

  it('1시간봉 넓은 범위 축은 월·연도 경계를 우선 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-01-01T00:00:00Z'), '1h', 1200)).toBe('2026')
    expect(formatKstAxisTick(ts('2026-05-05T23:00:00Z'), '1h', 1200)).toBe('5월')
    expect(formatKstAxisTick(ts('2026-05-11T00:00:00Z'), '1h', 1200)).toBe('11')
    expect(formatKstAxisTick(ts('2026-05-05T23:00:00Z'), '1h', 3200)).toBe('5월')
    expect(formatKstAxisTick(ts('2026-05-11T00:00:00Z'), '1h', 3200)).toBe('')
  })

  it('월 첫 봉이 1일이 아니어도 LWC 월 tick이면 x월로 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-05-11T00:00:00Z'), '1h', 1200, TickMarkType.Month)).toBe('5월')
    expect(formatKstAxisTick(ts('2026-05-11T00:00:00Z'), '1d', 1200, TickMarkType.Month)).toBe('5월')
  })

  it('캔들 데이터에서 일·월이 바뀐 첫 봉은 실제 날짜와 무관하게 경계 라벨로 표시한다', () => {
    const boundaries = buildAxisBoundaryTimes([
      ts('2026-04-30T00:00:00Z'),
      ts('2026-05-11T00:00:00Z'),
      ts('2026-05-12T00:00:00Z'),
    ])

    expect(formatKstAxisTick(
      ts('2026-05-11T00:00:00Z'),
      '1h',
      1200,
      TickMarkType.DayOfMonth,
      boundaries.month,
      boundaries.year,
      boundaries.day,
    )).toBe('5월')

    expect(formatKstAxisTick(
      ts('2026-05-12T00:00:00Z'),
      '1h',
      80,
      TickMarkType.Time,
      boundaries.month,
      boundaries.year,
      boundaries.day,
    )).toBe('12')
  })

  it('1시간봉 미래 whitespace는 주말을 건너 다음 영업일 시간축을 만든다', () => {
    const future = buildHourlyFutureWhitespaceTimes([
      ts('2026-06-05T01:00:00Z'),
      ts('2026-06-05T02:00:00Z'),
      ts('2026-06-05T03:00:00Z'),
      ts('2026-06-05T04:00:00Z'),
      ts('2026-06-05T05:00:00Z'),
      ts('2026-06-05T06:00:00Z'),
    ])

    expect(future).not.toContain(ts('2026-06-06T03:00:00Z'))
    expect(future).toContain(ts('2026-06-08T00:00:00Z'))
    expect(future).toContain(ts('2026-06-08T03:00:00Z'))
    expect(formatKstAxisTick(ts('2026-06-08T03:00:00Z'), '1h', 80, TickMarkType.Time)).toBe('12:00')
  })

  it('여러 날이 보이는 분봉은 날짜와 월 경계를 짧게 표시한다', () => {
    expect(formatKstAxisTick(ts('2026-06-04T00:00:00Z'), '1m', 700)).toBe('4')
    expect(formatKstAxisTick(ts('2026-06-04T15:00:00Z'), '1m', 700)).toBe('5')
    expect(formatKstAxisTick(ts('2026-05-31T15:00:00Z'), '1m', 700)).toBe('6월')
  })

  it('일봉 이상 축 라벨은 긴 날짜 대신 M/D, 월, 연도 단위로 줄인다', () => {
    expect(formatKstAxisTick(ts('2026-06-05T00:00:00Z'), '1d', 80)).toBe('6/5')
    expect(formatKstAxisTick(ts('2026-06-01T00:00:00Z'), '1d', 220)).toBe('6월')
    expect(formatKstAxisTick(ts('2026-01-01T00:00:00Z'), '1d', 400)).toBe('2026')
  })
})
