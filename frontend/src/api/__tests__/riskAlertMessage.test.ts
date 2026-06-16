import { describe, expect, it } from 'vitest'
import { buildRiskToastMessage } from '../riskAlertMessage'
import type { RiskEventDto } from '../risk'

describe('buildRiskToastMessage', () => {
  it('backend alert fields를 우선 사용해 한국어 수식 토스트를 만든다', () => {
    const event: RiskEventDto = {
      id: '1',
      userId: 74683223,
      verdict: 'BLOCKED',
      rule: 'TRADE_FREQUENCY_DAY',
      reason: '오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.',
      alertTitle: 'D. 거래 빈도 한도 도달 - 일별 최대 거래',
      alertMessage: '오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.',
      alertFormula: '오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일',
      ts: '2026-05-29T10:49:00Z',
    }

    expect(buildRiskToastMessage(event)).toBe(
      [
        '[차단] D. 거래 빈도 한도 도달 - 일별 최대 거래',
        '오늘 이 전략에서 허용된 주문 횟수를 모두 사용해 추가 자동 주문을 차단했습니다.',
        '수식: 오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일',
      ].join('\n'),
    )
  })

  it('legacy 영문 reason도 거래 빈도 룰이면 한국어 수식으로 보완한다', () => {
    const event: RiskEventDto = {
      id: '1',
      userId: 74683223,
      verdict: 'BLOCKED',
      rule: 'TRADE_FREQUENCY_DAY',
      reason: 'Strategy daily trade count 50 >= limit 50',
      ts: '2026-05-29T10:49:00Z',
    }

    const msg = buildRiskToastMessage(event)

    expect(msg).toContain('[차단] D. 거래 빈도 한도 도달 - 일별 최대 거래')
    expect(msg).toContain('오늘 허용 주문 수 50회 >= 일별 최대 거래 50회/일')
  })

  it('legacy POSITION_LIMIT reason을 종목 한도 계산식으로 보완한다', () => {
    const event: RiskEventDto = {
      id: '154',
      userId: 74683223,
      verdict: 'BLOCKED',
      rule: 'POSITION_LIMIT',
      reason: 'Unit price 351500 exceeds remaining position budget 297000 (budget=1000000, existing=703000, min(abs=1000000, pct=1192172.5000))',
      ts: '2026-06-02T04:30:01Z',
    }

    const msg = buildRiskToastMessage(event)

    expect(msg).toContain('[차단] A. 포지션 한도 초과')
    expect(msg).toContain('신호 단가 351,500원 > 남은 종목 한도 297,000원')
    expect(msg).toContain('남은 종목 한도 = 1,000,000원 - 기존 노출 703,000원 = 297,000원')
  })

  it('legacy SELL_QUANTITY_UNAVAILABLE reason을 매도 가능 수량 계산식으로 보완한다', () => {
    const event: RiskEventDto = {
      id: '152',
      userId: 74683223,
      verdict: 'BLOCKED',
      rule: 'SELL_QUANTITY_UNAVAILABLE',
      reason: 'SELL signal has no sellable quantity for code=005930 (holding=2, reservedSell=2)',
      ts: '2026-06-02T04:28:00Z',
    }

    const msg = buildRiskToastMessage(event)

    expect(msg).toContain('[차단] 매도 가능 수량 부족 - 미체결 매도 주문 보호')
    expect(msg).toContain('종목 005930 매도 가능 수량 = 보유 2주 - 미체결 매도 2주 = 0주')
  })
})
