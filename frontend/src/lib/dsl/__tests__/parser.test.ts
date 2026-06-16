/**
 * DSL 파서 단위 테스트 (5 fixture + 화이트리스트 위반)
 *
 * ADR 0022 §2-4 화이트리스트 위반 throw 검증
 */
import { describe, expect, it } from 'vitest'
import { parseDslText, parseDslTextToTree } from '../parser'

describe('parseDslText — KIS 호환 입력 → BlockTree', () => {
  it('fixture1 — rsi(14) < 30', () => {
    const tree = parseDslText('rsi(14) < 30')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.op).toBe('<')
      expect(tree.left?.kind).toBe('indicator')
      if (tree.left?.kind === 'indicator') {
        expect(tree.left.id).toBe('rsi')
      }
      expect(tree.right?.kind).toBe('literal')
    }
  })

  it('fixture2 — AND 논리', () => {
    const tree = parseDslText('(rsi(14) < 30) AND (ma(5) crosses_above ma(20))')
    expect(tree.kind).toBe('logical')
    if (tree.kind === 'logical') {
      expect(tree.op).toBe('AND')
      expect(tree.left?.kind).toBe('comparison')
      expect(tree.right?.kind).toBe('comparison')
    }
  })

  it('fixture3 — 산술 표현식', () => {
    const tree = parseDslText('volume > volume_ma(20) * 1.5')
    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.op).toBe('>')
      expect(tree.left?.kind).toBe('candle')
      expect(tree.right?.kind).toBe('arithmetic')
    }
  })

  it('prev_close 캔들 식별자를 산술 표현식에서 파싱한다', () => {
    const tree = parseDslText('open >= prev_close * 1.025')

    expect(tree.kind).toBe('comparison')
    if (tree.kind === 'comparison') {
      expect(tree.left).toEqual({ kind: 'candle', field: 'open' })
      expect(tree.right?.kind).toBe('arithmetic')
      if (tree.right?.kind === 'arithmetic') {
        expect(tree.right.left).toEqual({ kind: 'candle', field: 'prev_close' })
      }
    }
  })

  it('fixture4 — pattern_detected', () => {
    const tree = parseDslText('pattern_detected("hammer")')
    expect(tree.kind).toBe('pattern')
    if (tree.kind === 'pattern') {
      expect(tree.patternId).toBe('hammer')
      expect(tree.func).toBe('pattern_detected')
    }
  })

  it('fixture5 — OR 논리', () => {
    const tree = parseDslText('rsi(14) > 70 OR rsi(14) < 30')
    expect(tree.kind).toBe('logical')
    if (tree.kind === 'logical') {
      expect(tree.op).toBe('OR')
    }
  })
})

describe('parseDslText — 화이트리스트 위반 → throw', () => {
  it('미정의 함수 unknown_func() → ParseError', () => {
    expect(() => parseDslText('ma(5) crosses_above unknown_func()')).toThrow()
  })

  it('허용되지 않는 패턴 ID → ParseError', () => {
    expect(() => parseDslText('pattern_detected("not_a_real_pattern")')).toThrow()
  })
})

describe('parseDslTextToTree', () => {
  it('entry만 있는 경우', () => {
    const tree = parseDslTextToTree('rsi(14) < 30')
    expect(tree.entry).toBeDefined()
    expect(tree.exit).toBeUndefined()
  })

  it('빈 문자열은 undefined', () => {
    const tree = parseDslTextToTree('')
    expect(tree.entry).toBeUndefined()
  })
})
