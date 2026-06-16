/**
 * candleCountMaxByInterval — 매트릭스·clamp·localStorage 단위 테스트
 *
 * ADR 0093 §결정 LOCK Phase 4-F (frontend 단위 테스트):
 *   - timeframe별 max cap 매트릭스 정합 (의제 §Phase 4-C)
 *   - clampCandleCount: NaN/음수/0 → default 200 (ADR 0120 §6-2), 1 미만 → 1, max 초과 → max
 *   - read/writeStoredCandleCount: localStorage 영속 + invalid 시 default 회귀
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  CANDLE_COUNT_MAX_BY_INTERVAL,
  CANDLE_COUNT_PRESETS,
  CANDLE_COUNT_STORAGE_KEY,
  DEFAULT_CANDLE_COUNT,
  MIN_CANDLE_COUNT,
  clampCandleCount,
  readStoredCandleCount,
  writeStoredCandleCount,
} from '../candleCountMaxByInterval'

describe('CANDLE_COUNT_MAX_BY_INTERVAL — ADR 0093 §3-1 매트릭스 LOCK', () => {
  it('1m = 4800', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1m']).toBe(4800)
  })

  it('3m·5m·15m·30m·90m·1h = 3840 (합산 6종)', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['3m']).toBe(3840)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['5m']).toBe(3840)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['15m']).toBe(3840)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['30m']).toBe(3840)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['90m']).toBe(3840)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1h']).toBe(3840)
  })

  it('4h·12h 고타임프레임 합산 cap이 정합하다', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['4h']).toBe(2000)
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['12h']).toBe(1200)
  })

  it('1d = 5000', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1d']).toBe(5000)
  })

  it('1wk = 2000', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1wk']).toBe(2000)
  })

  it('1mo = 1200', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1mo']).toBe(1200)
  })

  it('1y = 600', () => {
    expect(CANDLE_COUNT_MAX_BY_INTERVAL['1y']).toBe(600)
  })
})

describe('CANDLE_COUNT_PRESETS — 의제 §Phase 4-A select dropdown + ADR 0120 §6-2', () => {
  it('preset = [200, 300, 600, 1200, 3000]', () => {
    expect([...CANDLE_COUNT_PRESETS]).toEqual([200, 300, 600, 1200, 3000])
  })
})

describe('DEFAULT_CANDLE_COUNT — ADR 0120 §6-2 조작 전 default 200', () => {
  it('DEFAULT = 200', () => {
    expect(DEFAULT_CANDLE_COUNT).toBe(200)
  })
})

describe('clampCandleCount', () => {
  it('preset 600 + 1d → 600 그대로 (max 5000 이하)', () => {
    expect(clampCandleCount(600, '1d')).toBe(600)
  })

  it('max 초과 입력 → max로 clamp (1d=5000)', () => {
    expect(clampCandleCount(9999, '1d')).toBe(5000)
  })

  it('1y interval에서 700 입력 → 600 (max 600)', () => {
    expect(clampCandleCount(700, '1y')).toBe(600)
  })

  it('1m interval에서 5000 입력 → 4800 (max 4800)', () => {
    expect(clampCandleCount(5000, '1m')).toBe(4800)
  })

  it('3m·5m·15m·30m·90m·1h interval에서 4000 → 3840 (합산 6종 max)', () => {
    expect(clampCandleCount(4000, '3m')).toBe(3840)
    expect(clampCandleCount(4000, '5m')).toBe(3840)
    expect(clampCandleCount(4000, '15m')).toBe(3840)
    expect(clampCandleCount(4000, '30m')).toBe(3840)
    expect(clampCandleCount(4000, '90m')).toBe(3840)
    expect(clampCandleCount(4000, '1h')).toBe(3840)
  })

  it('4h·12h interval 직접입력은 각 max로 clamp된다', () => {
    expect(clampCandleCount(4000, '4h')).toBe(2000)
    expect(clampCandleCount(4000, '12h')).toBe(1200)
  })

  it('NaN/음수/0 → DEFAULT_CANDLE_COUNT (200)', () => {
    expect(clampCandleCount(Number.NaN, '1d')).toBe(DEFAULT_CANDLE_COUNT)
    expect(clampCandleCount(-100, '1d')).toBe(DEFAULT_CANDLE_COUNT)
    expect(clampCandleCount(0, '1d')).toBe(DEFAULT_CANDLE_COUNT)
  })

  it('소수점 입력 → floor 후 clamp (0.5 → 1, 1.5 → 1)', () => {
    // 0.5 (양수) → finite·양수 path 통과 → floor → 0 → MIN(1)로 clamp
    expect(clampCandleCount(0.5, '1d')).toBe(MIN_CANDLE_COUNT)
    // 1.5 → floor → 1
    expect(clampCandleCount(1.5, '1d')).toBe(MIN_CANDLE_COUNT)
  })

  it('Infinity → DEFAULT (Number.isFinite 가드)', () => {
    expect(clampCandleCount(Number.POSITIVE_INFINITY, '1d')).toBe(DEFAULT_CANDLE_COUNT)
  })
})

describe('readStoredCandleCount / writeStoredCandleCount — localStorage 영속', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('저장값 없을 때 → DEFAULT_CANDLE_COUNT (200)', () => {
    expect(readStoredCandleCount()).toBe(DEFAULT_CANDLE_COUNT)
  })

  it('write 후 read → 같은 값', () => {
    writeStoredCandleCount(1200)
    expect(readStoredCandleCount()).toBe(1200)
    expect(window.localStorage.getItem(CANDLE_COUNT_STORAGE_KEY)).toBe('1200')
  })

  it('invalid 저장값 → DEFAULT 회귀 (safety net)', () => {
    window.localStorage.setItem(CANDLE_COUNT_STORAGE_KEY, 'not-a-number')
    expect(readStoredCandleCount()).toBe(DEFAULT_CANDLE_COUNT)
  })

  it('음수 저장값 → DEFAULT 회귀', () => {
    window.localStorage.setItem(CANDLE_COUNT_STORAGE_KEY, '-500')
    expect(readStoredCandleCount()).toBe(DEFAULT_CANDLE_COUNT)
  })

  it('key = "magicjar:chart:candleCount" (전역 1개 — symbol/timeframe 무관)', () => {
    expect(CANDLE_COUNT_STORAGE_KEY).toBe('magicjar:chart:candleCount')
  })
})
