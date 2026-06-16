import { describe, expect, it } from 'vitest'
import type { BlockNode } from '../blockTree'
import { parseDslText } from '../parser'
import { serializeBlockTree, serializeNode_ } from '../serializer'
import { SMART_MONEY_STRATEGY_PRESETS } from '../smartMoneyPresets'

describe('SMART_MONEY_STRATEGY_PRESETS', () => {
  it('FVG/OB 후보 프리셋을 DSL alias로 직렬화한다', () => {
    const byId = new Map(SMART_MONEY_STRATEGY_PRESETS.map((preset) => [preset.id, preset]))

    expect(serializeBlockTree(byId.get('fvg-bullish-reclaim')!.tree)).toEqual({
      entry: '(fvg_bullish_active(50) == 1) AND (close > fvg_bullish_mid(50))',
      exit: 'close < fvg_bullish_bottom(50)',
    })
    expect(serializeBlockTree(byId.get('order-block-bullish-retest')!.tree).entry).toBe(
      '(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))',
    )
  })

  it('모든 프리셋은 추천 기준봉과 entry/exit를 가진다', () => {
    for (const preset of SMART_MONEY_STRATEGY_PRESETS) {
      const dsl = serializeBlockTree(preset.tree)
      expect(preset.suggestedInterval).toBeTruthy()
      expect(dsl.entry).toBeTruthy()
      expect(dsl.exit).toBeTruthy()
    }
  })

  it('기존 프리셋 4종 회귀 0 — id·순서 유지', () => {
    expect(SMART_MONEY_STRATEGY_PRESETS.slice(0, 4).map((p) => p.id)).toEqual([
      'fvg-bullish-reclaim',
      'order-block-bullish-retest',
      'fvg-bearish-reject',
      'order-block-bearish-retest',
    ])
  })
})

describe('OB 장악형(Engulfing) 프리셋 — detection/range 파라미터 노출', () => {
  const byId = new Map(SMART_MONEY_STRATEGY_PRESETS.map((preset) => [preset.id, preset]))

  it('장악형 프리셋 2종이 기존 4종 뒤에 추가된다', () => {
    expect(SMART_MONEY_STRATEGY_PRESETS).toHaveLength(6)
    expect(SMART_MONEY_STRATEGY_PRESETS.slice(4).map((p) => p.id)).toEqual([
      'order-block-engulfing-bullish-retest',
      'order-block-engulfing-bearish-retest',
    ])
  })

  it('매수 장악형 프리셋 구조·라벨·기준봉', () => {
    const preset = byId.get('order-block-engulfing-bullish-retest')!
    expect(preset.label).toBe('OB 장악형 매수 후보')
    expect(preset.suggestedInterval).toBe('3m')
    const dsl = serializeBlockTree(preset.tree)
    expect(dsl.entry).toBe(
      '(ob_bullish_active(20, 20, 100, engulfing, body) == 1) AND ' +
        '(close > ob_bullish_mid(20, 20, 100, engulfing, body))',
    )
    expect(dsl.exit).toBe('close < ob_bullish_bottom(20, 20, 100, engulfing, body)')
  })

  it('매도 장악형 프리셋 구조·라벨', () => {
    const preset = byId.get('order-block-engulfing-bearish-retest')!
    expect(preset.label).toBe('OB 장악형 매도 후보')
    const dsl = serializeBlockTree(preset.tree)
    expect(dsl.entry).toBe(
      '(ob_bearish_active(20, 20, 100, engulfing, body) == 1) AND ' +
        '(close < ob_bearish_mid(20, 20, 100, engulfing, body))',
    )
    expect(dsl.exit).toBe('close > ob_bearish_top(20, 20, 100, engulfing, body)')
  })

  it('detection/range 위치 직렬화 계약 — order_block(20, 20, 100, engulfing, body)', () => {
    // outputKey 없는 raw order_block 노드 = backend KisYamlDslParser 입력 형태
    const node: BlockNode = {
      kind: 'indicator',
      id: 'order_block',
      params: {
        swingLookback: 20,
        searchLookback: 20,
        mitigationPct: 100,
        detection: 'engulfing',
        range: 'body',
      },
    }
    expect(serializeNode_(node)).toBe('order_block(20, 20, 100, engulfing, body)')
  })

  it('round-trip — serialize → parse → serialize 안정 (IDENTIFIER 인자 보존)', () => {
    const original = 'order_block(20, 20, 100, engulfing, body)'
    const parsed = parseDslText(original)
    expect(parsed.kind).toBe('indicator')
    if (parsed.kind === 'indicator') {
      expect(parsed.id).toBe('order_block')
      // 위치 인자 값 5개가 순서대로 보존되어야 한다
      expect(Object.values(parsed.params)).toEqual([20, 20, 100, 'engulfing', 'body'])
    }
    // 재직렬화 시 동일 DSL 문자열 복원
    expect(serializeNode_(parsed)).toBe(original)
  })

  it('round-trip — bos/wick default 인자도 보존', () => {
    const original = 'order_block(20, 20, 50, bos, wick)'
    const parsed = parseDslText(original)
    expect(serializeNode_(parsed)).toBe(original)
  })
})
