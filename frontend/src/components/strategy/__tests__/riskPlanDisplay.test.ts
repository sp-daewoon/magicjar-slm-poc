import { describe, expect, it } from 'vitest'
import {
  buildRiskPlanLevels,
  formatRiskPlanPct,
  formatRiskPlanPrice,
  parsePositiveNumber,
} from '../riskPlanDisplay'

describe('riskPlanDisplay', () => {
  it('매수 기준가에서 손절가와 익절가를 계산한다', () => {
    expect(buildRiskPlanLevels(100_000, 5, 10)).toMatchObject({
      entryPrice: 100_000,
      stopPrice: 95_000,
      takePrice: 110_000,
      rewardRiskRatio: 2,
    })
  })

  it('단타형 RiskPlan은 익절 기준이 손절 기준 이하라도 preview levels를 만든다', () => {
    expect(buildRiskPlanLevels(100_000, 5, 5)).toMatchObject({
      stopPrice: 95_000,
      takePrice: 105_000,
      rewardRiskRatio: 1,
    })
    expect(buildRiskPlanLevels(100_000, 5, 3)).toMatchObject({
      stopPrice: 95_000,
      takePrice: 103_000,
      rewardRiskRatio: 0.6,
    })
  })

  it('숫자와 가격을 한국어 표시 형식으로 정리한다', () => {
    expect(parsePositiveNumber('5.50')).toBe(5.5)
    expect(formatRiskPlanPct('5.50')).toBe('5.5')
    expect(formatRiskPlanPrice(123456.2)).toBe('123,456원')
  })
})
