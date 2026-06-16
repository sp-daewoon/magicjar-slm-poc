import { describe, expect, it } from 'vitest'
import {
  EXIT_LIMITS,
  SCALE_IN_LIMITS,
  describeScaleIn,
  evenSliceWeights,
  isScaleInNotEnabledError,
  sumWeights,
  validatePartialExits,
  validateScaleIn,
} from '../scaleInExitDisplay'

describe('scaleInExitDisplay — 균등 분할 비중', () => {
  it('100/N을 계산하고 마지막 회차가 나머지를 흡수해 합이 100', () => {
    const w = evenSliceWeights(3)
    expect(w).toHaveLength(3)
    expect(sumWeights(w)).toBeCloseTo(100, 5)
  })

  it('2분할은 50/50', () => {
    expect(evenSliceWeights(2)).toEqual([50, 50])
  })
})

describe('scaleInExitDisplay — validateScaleIn (도메인 ScaleInPlan.init 규칙)', () => {
  it('회수 경계 1·10은 통과, 0·11은 거부', () => {
    expect(validateScaleIn({ totalSlices: 1 }).ok).toBe(true)
    expect(validateScaleIn({ totalSlices: SCALE_IN_LIMITS.MAX_SLICES }).ok).toBe(true)
    expect(validateScaleIn({ totalSlices: 0 }).ok).toBe(false)
    expect(validateScaleIn({ totalSlices: 11 }).ok).toBe(false)
  })

  it('수동 비중 합 100은 통과, 99·101은 거부 (오차 ±0.01)', () => {
    expect(validateScaleIn({ totalSlices: 3, sliceWeights: [50, 30, 20] }).ok).toBe(true)
    expect(validateScaleIn({ totalSlices: 3, sliceWeights: [50, 30, 19] }).ok).toBe(false)
    expect(validateScaleIn({ totalSlices: 3, sliceWeights: [50, 30, 21] }).ok).toBe(false)
    // 오차 허용 경계
    expect(validateScaleIn({ totalSlices: 2, sliceWeights: [50, 50.005] }).ok).toBe(true)
  })

  it('수동 비중 개수가 회수와 다르면 거부', () => {
    expect(validateScaleIn({ totalSlices: 3, sliceWeights: [50, 50] }).ok).toBe(false)
  })

  it('음수·0 비중은 거부', () => {
    expect(validateScaleIn({ totalSlices: 2, sliceWeights: [120, -20] }).ok).toBe(false)
    expect(validateScaleIn({ totalSlices: 2, sliceWeights: [100, 0] }).ok).toBe(false)
  })

  it('PRICE_DROP_PCT는 음수 하락폭 필수', () => {
    expect(validateScaleIn({ totalSlices: 3, reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: -3 }).ok).toBe(true)
    expect(validateScaleIn({ totalSlices: 3, reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: 3 }).ok).toBe(false)
    expect(validateScaleIn({ totalSlices: 3, reentryTrigger: 'PRICE_DROP_PCT', priceDropPct: null }).ok).toBe(false)
  })

  it('SIGNAL_REPEAT는 하락폭 없어도 통과', () => {
    expect(validateScaleIn({ totalSlices: 3, reentryTrigger: 'SIGNAL_REPEAT' }).ok).toBe(true)
  })
})

describe('scaleInExitDisplay — validatePartialExits (도메인 ExitLimits 규칙)', () => {
  it('단계 ≤ 5는 통과, 6은 거부', () => {
    const five = Array.from({ length: EXIT_LIMITS.MAX_PARTIAL_EXIT_STAGES }, (_, i) => ({
      triggerProfitPct: i + 1,
      exitWeightPct: 10,
    }))
    expect(validatePartialExits(five).ok).toBe(true)
    expect(validatePartialExits([...five, { triggerProfitPct: 9, exitWeightPct: 10 }]).ok).toBe(false)
  })

  it('weight 합 100은 통과, 101은 거부', () => {
    expect(validatePartialExits([{ triggerProfitPct: 3, exitWeightPct: 50 }, { triggerProfitPct: 7, exitWeightPct: 50 }]).ok).toBe(true)
    expect(validatePartialExits([{ triggerProfitPct: 3, exitWeightPct: 60 }, { triggerProfitPct: 7, exitWeightPct: 41 }]).ok).toBe(false)
  })

  it('수익률·비중 음수/범위 초과 거부', () => {
    expect(validatePartialExits([{ triggerProfitPct: 0, exitWeightPct: 50 }]).ok).toBe(false)
    expect(validatePartialExits([{ triggerProfitPct: 3, exitWeightPct: 0 }]).ok).toBe(false)
    expect(validatePartialExits([{ triggerProfitPct: 3, exitWeightPct: 101 }]).ok).toBe(false)
  })

  it('빈 배열은 통과 (미사용)', () => {
    expect(validatePartialExits([]).ok).toBe(true)
  })
})

describe('scaleInExitDisplay — 보조 함수', () => {
  it('SCALE_IN_NOT_ENABLED 에러 판별', () => {
    expect(isScaleInNotEnabledError('SCALE_IN_NOT_ENABLED: ...')).toBe(true)
    expect(isScaleInNotEnabledError('Request failed: SCALE_IN_NOT_ENABLED')).toBe(true)
    expect(isScaleInNotEnabledError('other error')).toBe(false)
    expect(isScaleInNotEnabledError(null)).toBe(false)
  })

  it('describeScaleIn 요약 라벨', () => {
    expect(describeScaleIn(null)).toBeNull()
    expect(describeScaleIn({ totalSlices: 3, reentryTrigger: 'SIGNAL_REPEAT' })).toBe('3분할 · 시그널 재발화')
    expect(describeScaleIn({ totalSlices: 2, reentryTrigger: 'PRICE_DROP_PCT' })).toBe('2분할 · 가격 하락 재진입')
  })
})
