/**
 * DslSerializer round-trip 테스트 (5 fixture)
 *
 * fixture 1: rsi(14) < 30
 * fixture 2: (rsi(14) < 30) AND (ma(5) crosses_above ma(20))
 * fixture 3: volume > volume_ma(20) * 1.5
 * fixture 4: pattern_detected("hammer")
 * fixture 5: bb_upper(20, 2.0) > close
 *
 * ADR 0055 §6 화이트리스트 매트릭스 LOCK 검증
 */
import { describe, expect, it } from 'vitest'
import { serializeBlockTree, serializeNode_ } from '../serializer'
import { parseDslText } from '../parser'
import type { BlockTree, BlockNode } from '../blockTree'

describe('DslSerializer round-trip', () => {
  /**
   * fixture 1: rsi(14) < 30
   */
  it('fixture1 — rsi(14) < 30', () => {
    const node: BlockNode = {
      kind: 'comparison',
      op: '<',
      left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
      right: { kind: 'literal', valueType: 'integer', value: '30' },
    }
    const dsl = serializeNode_(node)
    expect(dsl).toBe('rsi(14) < 30')

    // round-trip: parse 후 다시 직렬화
    const reparsed = parseDslText(dsl)
    const reDsl = serializeNode_(reparsed)
    expect(reDsl).toBe('rsi(14) < 30')
  })

  /**
   * fixture 2: (rsi(14) < 30) AND (ma(5) crosses_above ma(20))
   */
  it('fixture2 — AND 논리 결합', () => {
    const node: BlockNode = {
      kind: 'logical',
      op: 'AND',
      left: {
        kind: 'comparison',
        op: '<',
        left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
        right: { kind: 'literal', valueType: 'integer', value: '30' },
      },
      right: {
        kind: 'comparison',
        op: 'crosses_above',
        left: { kind: 'indicator', id: 'ma', params: { p0: 5 } },
        right: { kind: 'indicator', id: 'ma', params: { p0: 20 } },
      },
    }
    const dsl = serializeNode_(node)
    expect(dsl).toContain('AND')
    expect(dsl).toContain('rsi(14) < 30')
    expect(dsl).toContain('ma(5) crosses_above ma(20)')

    // round-trip
    const reparsed = parseDslText(dsl)
    const reDsl = serializeNode_(reparsed)
    expect(reDsl).toBe(dsl)
  })

  /**
   * fixture 3: volume > volume_ma(20) * 1.5
   */
  it('fixture3 — 산술 표현식 volume > volume_ma(20) * 1.5', () => {
    const node: BlockNode = {
      kind: 'comparison',
      op: '>',
      left: { kind: 'candle', field: 'volume' },
      right: {
        kind: 'arithmetic',
        op: '*',
        left: { kind: 'indicator', id: 'volume_ma', params: { p0: 20 } },
        right: { kind: 'literal', valueType: 'float', value: '1.5' },
      },
    }
    const dsl = serializeNode_(node)
    expect(dsl).toBe('volume > volume_ma(20) * 1.5')

    // round-trip
    const reparsed = parseDslText(dsl)
    const reDsl = serializeNode_(reparsed)
    expect(reDsl).toBe(dsl)
  })

  it('prev_close 캔들 식별자를 직렬화하고 다시 파싱한다', () => {
    const node: BlockNode = {
      kind: 'comparison',
      op: '>=',
      left: { kind: 'candle', field: 'open' },
      right: {
        kind: 'arithmetic',
        op: '*',
        left: { kind: 'candle', field: 'prev_close' },
        right: { kind: 'literal', valueType: 'float', value: '1.025' },
      },
    }

    const dsl = serializeNode_(node)
    expect(dsl).toBe('open >= prev_close * 1.025')
    expect(serializeNode_(parseDslText(dsl))).toBe(dsl)
  })

  /**
   * fixture 4: pattern_detected("hammer")
   */
  it('fixture4 — pattern_detected("hammer")', () => {
    const node: BlockNode = {
      kind: 'pattern',
      patternId: 'hammer',
      func: 'pattern_detected',
    }
    const dsl = serializeNode_(node)
    expect(dsl).toBe('pattern_detected("hammer")')

    // round-trip
    const reparsed = parseDslText(dsl)
    const reDsl = serializeNode_(reparsed)
    expect(reDsl).toBe('pattern_detected("hammer")')
  })

  /**
   * fixture 5: bb_upper(20, 2.0) > close
   * multi-output alias 직렬화 LOCK
   */
  it('fixture5 — bb_upper(20, 2.0) > close multi-output alias', () => {
    const node: BlockNode = {
      kind: 'comparison',
      op: '>',
      left: {
        kind: 'indicator',
        id: 'bb',
        params: { p0: 20, p1: 2.0 },
        outputKey: 'upper',
      },
      right: { kind: 'candle', field: 'close' },
    }
    const dsl = serializeNode_(node)
    expect(dsl).toBe('bb_upper(20, 2) > close')
  })
})

describe('serializeBlockTree — entry/exit 두 트리', () => {
  it('entry만 있는 경우 exit은 빈 문자열', () => {
    const tree: BlockTree = {
      entry: {
        kind: 'comparison',
        op: '<',
        left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
        right: { kind: 'literal', valueType: 'integer', value: '30' },
      },
    }
    const { entry, exit } = serializeBlockTree(tree)
    expect(entry).toBe('rsi(14) < 30')
    expect(exit).toBe('')
  })

  it('빈 트리 — entry·exit 모두 빈 문자열', () => {
    const { entry, exit } = serializeBlockTree({})
    expect(entry).toBe('')
    expect(exit).toBe('')
  })
})
