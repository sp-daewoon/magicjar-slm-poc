/**
 * KIS YAML export/import 테스트 (3 fixture)
 */
import { describe, expect, it } from 'vitest'
import { kisYamlExport, kisYamlImport } from '../yaml'
import type { BlockTree } from '../blockTree'

describe('kisYamlExport', () => {
  it('fixture1 — entry만 있는 전략', () => {
    const tree: BlockTree = {
      entry: {
        kind: 'comparison',
        op: '<',
        left: { kind: 'indicator', id: 'rsi', params: { p0: 14 } },
        right: { kind: 'literal', valueType: 'integer', value: '30' },
      },
    }
    const yaml = kisYamlExport(tree, { name: 'rsi_buy', nameKo: 'RSI 매수 전략', universe: 'KOSPI', tf: '1d' })
    expect(yaml).toContain('buy_condition:')
    expect(yaml).toContain('rsi(14) < 30')
    expect(yaml).toContain('universe: KOSPI')
    expect(yaml).toContain('tf: 1d')
    expect(yaml).toContain('name: rsi_buy')
    expect(yaml).not.toContain('sell_condition:')
  })
})

describe('kisYamlImport', () => {
  it('fixture2 — buy_condition 추출', () => {
    const yaml = `
name: test_strategy
buy_condition: "rsi(14) < 30"
universe: KOSPI
tf: 1d
`
    const result = kisYamlImport(yaml)
    expect(result.entry).toBe('rsi(14) < 30')
    expect(result.universe).toBe('KOSPI')
    expect(result.tf).toBe('1d')
  })

  it('fixture3 — buy + sell 모두 있는 경우', () => {
    const yaml = `
buy_condition: "rsi(14) < 30"
sell_condition: "rsi(14) > 70"
`
    const result = kisYamlImport(yaml)
    expect(result.entry).toBe('rsi(14) < 30')
    expect(result.exit).toBe('rsi(14) > 70')
  })

  it('buy_condition 없는 경우 → throw', () => {
    const yaml = `
name: no_condition
universe: KOSPI
`
    expect(() => kisYamlImport(yaml)).toThrow()
  })

  it('1MB 초과 → throw', () => {
    const big = 'x'.repeat(1024 * 1024 + 1)
    expect(() => kisYamlImport(big)).toThrow()
  })
})
