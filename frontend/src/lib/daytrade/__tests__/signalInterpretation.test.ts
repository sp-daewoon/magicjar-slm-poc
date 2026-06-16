import { describe, expect, it } from 'vitest'
import {
  buildDaytradeSignalAiContext,
  buildDaytradeSignalContext,
  type DaytradeSignalMatchLike,
} from '../signalInterpretation'

const match: DaytradeSignalMatchLike = {
  rank: 1,
  instrumentCode: '005930',
  instrumentName: '삼성전자',
  score: 0.82,
  scoreComponents: { signal: 0.9, volume: 0.7, atr: 0.5, cap: 1 },
  reason:
    '[DAYTRADE_GAP_AND_GO] 갭 앤 고 — {trigger=COMPOSITE, operator=AND, left={trigger=THRESHOLD, operator=>=, lhs=open, lhsValue=70500, rhs=prev_close, rhsValue=69000}, right={trigger=BREAKOUT, lhs=close, lhsValue=71800, rhs=high(20), rhsValue=71200}}',
}

describe('daytrade signal interpretation', () => {
  it('Kotlin Map 형태의 DSL 평가 사유를 한국어 코멘트로 풀어낸다', () => {
    const signal = buildDaytradeSignalContext(match)

    expect(signal.presetKey).toBe('DAYTRADE_GAP_AND_GO')
    expect(signal.presetTitle).toBe('갭 앤 고')
    expect(signal.summary).toContain('삼성전자')
    expect(signal.summary).toContain('82.0점')
    expect(signal.reasonComments.join(' ')).toContain('AND 조합')
    expect(signal.reasonComments.join(' ')).toContain('시가')
    expect(signal.reasonComments.join(' ')).toContain('전일 종가')
    expect(signal.reasonComments.join(' ')).toContain('돌파 기준')
    expect(signal.scoreComments.join(' ')).toContain('시그널')
  })

  it('AI SIGNAL 프롬프트에 넣을 compact context를 만든다', () => {
    const signal = buildDaytradeSignalContext(match)
    const context = buildDaytradeSignalAiContext(signal)

    expect(context).toMatchObject({
      code: '005930',
      signalKind: '단타 발굴 BUY 후보',
      firedAt: '발굴 순위 1위',
      strategyName: '단타 발굴: 갭 앤 고',
    })
    expect(String(context.symbolState)).toContain('로컬 해석')
    expect(String(context.symbolState)).toContain('원문 사유')
  })
})
