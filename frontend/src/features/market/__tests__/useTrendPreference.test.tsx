/**
 * useTrendPreference + detectPresetId 단위 테스트 — T-MARKET R2 frontend.
 *
 * 검증:
 * - detectPresetId — 4 프리셋 (classic·mid·long·custom) 분기
 * - TREND_PRESETS — 카탈로그 LOCK
 */
import { describe, it, expect } from 'vitest'
import {
  detectPresetId,
  TREND_PRESETS,
} from '../../../api/hooks/useTrendPreference'

describe('detectPresetId', () => {
  it('classic 매칭 — [5,20,60]', () => {
    expect(detectPresetId([5, 20, 60])).toBe('classic')
  })

  it('mid 매칭 — [10,30,90]', () => {
    expect(detectPresetId([10, 30, 90])).toBe('mid')
  })

  it('long 매칭 — [20,60,120]', () => {
    expect(detectPresetId([20, 60, 120])).toBe('long')
  })

  it('순서 다름 → custom', () => {
    expect(detectPresetId([60, 20, 5])).toBe('custom')
  })

  it('개수 다름 → custom', () => {
    expect(detectPresetId([5, 20, 60, 120])).toBe('custom')
  })

  it('값 다름 → custom', () => {
    expect(detectPresetId([5, 20, 100])).toBe('custom')
  })

  it('단일 period → custom (3 프리셋 모두 3개)', () => {
    expect(detectPresetId([20])).toBe('custom')
  })
})

describe('TREND_PRESETS 카탈로그', () => {
  it('3 preset LOCK (id: classic·mid·long)', () => {
    const ids = TREND_PRESETS.map((p) => p.id)
    expect(ids).toEqual(['classic', 'mid', 'long'])
  })

  it('각 preset 한국어 label 포함', () => {
    expect(TREND_PRESETS[0].label).toMatch(/전통/)
    expect(TREND_PRESETS[1].label).toMatch(/중기/)
    expect(TREND_PRESETS[2].label).toMatch(/장기/)
  })

  it('classic preset = [5,20,60]', () => {
    const classic = TREND_PRESETS.find((p) => p.id === 'classic')
    expect(classic?.maPeriods).toEqual([5, 20, 60])
  })
})
