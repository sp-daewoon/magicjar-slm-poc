import { describe, expect, it } from 'vitest'
import {
  formatStrategyCodeLabel,
  formatStrategyTargetLabel,
  resolveStrategyMutationErrorMessage,
  strategyActivationBlockedReason,
} from '../strategyDisplay'

describe('strategyDisplay', () => {
  it('종목명이 있으면 종목명과 코드를 함께 표시한다', () => {
    expect(formatStrategyCodeLabel('000660', { '000660': 'SK하이닉스' })).toBe('SK하이닉스(000660)')
  })

  it('전략 대상 라벨도 종목명을 함께 사용한다', () => {
    expect(
      formatStrategyTargetLabel({
        codes: ['000660'],
        codeNames: { '000660': 'SK하이닉스' },
        intervals: ['15m'],
        tf: '15m',
      }),
    ).toBe('SK하이닉스(000660) / 15m')
  })

  it('Spring 409 응답에서 Conflict보다 detail을 우선 표시한다', () => {
    expect(
      resolveStrategyMutationErrorMessage({
        response: {
          status: 409,
          data: {
            error: 'Conflict',
            detail: '성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다: id=43',
          },
        },
      }),
    ).toBe('성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다: id=43')
  })

  it('detail 없는 Conflict는 자동매매 백테스트 게이트 메시지로 바꾼다', () => {
    expect(
      resolveStrategyMutationErrorMessage({
        response: {
          status: 409,
          data: { error: 'Conflict' },
        },
      }),
    ).toBe('성공한 백테스트가 있어야 자동매매를 시작할 수 있습니다.')
  })

  it('activationReady=false면 차단 사유를 반환한다', () => {
    expect(
      strategyActivationBlockedReason({
        id: '43',
        userId: 74683223,
        name: '1',
        codes: ['000660'],
        intervals: ['15m'],
        status: 'PAUSED',
        createdAt: '',
        activationReady: false,
        activationBlockedReason: '성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다',
      }),
    ).toBe('성공한 백테스트가 있어야 전략을 ACTIVE로 전환할 수 있습니다')
  })

  it('RiskPlan 차단 사유도 그대로 표시한다', () => {
    expect(
      strategyActivationBlockedReason({
        id: '43',
        userId: 74683223,
        name: '1',
        codes: ['005930'],
        intervals: ['1m'],
        status: 'ACTIVE',
        createdAt: '',
        activationReady: false,
        activationBlockedReason: '손절/익절 RiskPlan이 없어 자동매매를 시작할 수 없습니다',
      }),
    ).toBe('손절/익절 RiskPlan이 없어 자동매매를 시작할 수 없습니다')
  })
})
