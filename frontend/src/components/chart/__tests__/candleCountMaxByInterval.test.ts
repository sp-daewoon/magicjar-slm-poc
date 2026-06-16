/**
 * candleCountMaxByInterval — ADR 0093 §3-1 매트릭스 회귀 검증
 *
 * Phase 5 chart-curator (보수 페르소나 lens — "검증된 패턴 우선" 단위 테스트):
 * - 매트릭스 LOCK (ADR 0093 §3-1 정합 — 변경 시 ADR amend 의무)
 * - fallback 동작 검증 (보수적 방어 프로그래밍)
 */
import { describe, it, expect } from 'vitest'
import {
  CANDLE_COUNT_MAX_BY_INTERVAL,
  getCandleCountMax,
} from '../candleCountMaxByInterval'
import type { CandleInterval } from '../../../api/symbol'

describe('candleCountMaxByInterval — ADR 0093 §3-1 LOCK', () => {
  it('1d max cap = 5000 (약 20년 cover)', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1d']).toBe(5000)
    expect(getCandleCountMax('1d')).toBe(5000)
  })

  it('1m max cap = 4800 (약 13 거래일)', () => {
    expect(getCandleCountMax('1m')).toBe(4800)
  })

  it('합산 6종 (3m·5m·15m·30m·90m·1h) max cap = 3840', () => {
    expect(getCandleCountMax('3m')).toBe(3840)
    expect(getCandleCountMax('5m')).toBe(3840)
    expect(getCandleCountMax('15m')).toBe(3840)
    expect(getCandleCountMax('30m')).toBe(3840)
    expect(getCandleCountMax('90m')).toBe(3840)
    expect(getCandleCountMax('1h')).toBe(3840)
  })

  it('고타임프레임 합산 2종 max cap이 정합하다', () => {
    expect(getCandleCountMax('4h')).toBe(2000)
    expect(getCandleCountMax('12h')).toBe(1200)
  })

  it('1wk·1mo·1y max cap 매트릭스', () => {
    expect(getCandleCountMax('1wk')).toBe(2000)
    expect(getCandleCountMax('1mo')).toBe(1200)
    expect(getCandleCountMax('1y')).toBe(600)
  })

  it('매트릭스 readonly — 외부 mutation 차단 (Object.freeze)', () => {
    expect(Object.isFrozen(CANDLE_COUNT_MAX_BY_INTERVAL)).toBe(true)
  })

  it('모든 timeframe에 양수 cap 존재', () => {
    const intervals: CandleInterval[] = ['1m', '3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h', '1d', '1wk', '1mo', '1y']
    intervals.forEach((i) => {
      expect(getCandleCountMax(i)).toBeGreaterThan(0)
    })
  })

  it('미정의 interval은 1d fallback (보수 페르소나 — 방어 프로그래밍)', () => {
    // 강제 cast로 미지원 interval 시뮬레이션
    const unknown = 'foo' as unknown as CandleInterval
    expect(getCandleCountMax(unknown)).toBe(5000)
  })
})
