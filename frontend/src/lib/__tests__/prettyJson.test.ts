/**
 * prettyJson 단위 테스트 — 시스템 이벤트 Detail pretty 변환.
 */
import { describe, it, expect } from 'vitest'
import { prettyJson } from '../prettyJson'

describe('prettyJson', () => {
  it('유효한 JSON object 를 2-space indent 로 pretty-print 한다', () => {
    const { text, isJson } = prettyJson('{"signalId":958,"strategyId":86}')
    expect(isJson).toBe(true)
    expect(text).toBe('{\n  "signalId": 958,\n  "strategyId": 86\n}')
  })

  it('JSON array 도 pretty-print 한다', () => {
    const { text, isJson } = prettyJson('[1,2,3]')
    expect(isJson).toBe(true)
    expect(text).toContain('\n')
  })

  it('빈 문자열은 빈 결과 + isJson=false', () => {
    expect(prettyJson('')).toEqual({ text: '', isJson: false })
    expect(prettyJson('   ')).toEqual({ text: '', isJson: false })
    expect(prettyJson(null)).toEqual({ text: '', isJson: false })
    expect(prettyJson(undefined)).toEqual({ text: '', isJson: false })
  })

  it('일반 텍스트는 trim 후 원문 유지 + isJson=false', () => {
    expect(prettyJson('  reconnect attempt 3  ')).toEqual({
      text: 'reconnect attempt 3',
      isJson: false,
    })
  })

  it('스칼라 문자열(JSON 아님)은 원문 유지', () => {
    expect(prettyJson('123')).toEqual({ text: '123', isJson: false })
    expect(prettyJson('true')).toEqual({ text: 'true', isJson: false })
  })

  it('잘린(invalid) JSON 은 원문 그대로 노출 — 정보 손실 0', () => {
    const broken = '{"signalId":958,"strate'
    const { text, isJson } = prettyJson(broken)
    expect(isJson).toBe(false)
    expect(text).toBe(broken)
  })
})
