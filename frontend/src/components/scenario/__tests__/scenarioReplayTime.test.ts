import { describe, expect, it } from 'vitest'
import { defaultKstInput, isoToKstInput, kstInputToIso } from '../scenarioReplayTime'

describe('scenarioReplayTime', () => {
  it('KST datetime-local 입력을 UTC ISO로 변환한다', () => {
    expect(kstInputToIso('2026-06-02T14:00')).toBe('2026-06-02T05:00:00.000Z')
    expect(kstInputToIso('2026-06-02T09:00')).toBe('2026-06-02T00:00:00.000Z')
  })

  it('UTC ISO를 KST datetime-local 표시값으로 변환한다', () => {
    expect(isoToKstInput('2026-06-02T05:00:00Z')).toBe('2026-06-02T14:00')
    expect(isoToKstInput('2026-06-01T15:00:00Z')).toBe('2026-06-02T00:00')
  })

  it('기본 시각도 브라우저 timezone이 아니라 KST 날짜 기준으로 만든다', () => {
    const now = new Date('2026-06-03T00:30:00.000Z')
    expect(defaultKstInput(1, 13, 0, now)).toBe('2026-06-02T13:00')
    expect(defaultKstInput(1, 14, 10, now)).toBe('2026-06-02T14:10')
  })
})
