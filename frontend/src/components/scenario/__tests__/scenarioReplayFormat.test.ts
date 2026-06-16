import { describe, expect, it } from 'vitest'
import { eventLabel } from '../scenarioReplayFormat'

describe('eventLabel', () => {
  it('주요 이벤트 타입을 한국어 라벨로 매핑한다', () => {
    expect(eventLabel('SIGNAL_BUY')).toBe('매수 신호')
    expect(eventLabel('ORDER_FILLED')).toBe('가상 체결')
    expect(eventLabel('RISK_BLOCKED')).toBe('리플레이 리스크 차단')
  })

  it('GATE 이벤트를 raw enum 대신 한국어 라벨로 노출한다', () => {
    expect(eventLabel('GATE_SKIPPED')).toBe('게이트 차단(장운영시간 외)')
    expect(eventLabel('GATE_NOT_SIMULATED')).toBe('게이트 미재현(실계좌 상태 필요)')
  })

  it('매핑이 없는 타입은 원본 문자열을 그대로 반환한다', () => {
    expect(eventLabel('UNKNOWN_TYPE')).toBe('UNKNOWN_TYPE')
  })
})
