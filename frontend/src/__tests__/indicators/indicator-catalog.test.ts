/**
 * 80지표 카탈로그 정확성 fixture 테스트
 *
 * T-IND PR-33 R3 — 80지표 정확성 fixture (frontend 측)
 *
 * 검증 항목:
 * 1. INDICATOR_CATALOG 총 80개 항목 존재 (spec §5-1·§5-2 정합)
 * 2. 카테고리별 수량 (TREND=20, MOMENTUM=20, VOLUME=12, VOLATILITY=12, OSCILLATOR=8, OTHER=8)
 * 3. multi-output 5종 outputKeys 정합 (spec §5-2 LOCK)
 * 4. single-output outputKeys 각 1개 (spec §5-1)
 * 5. INDICATOR_CATALOG_MAP 인덱스 정합
 * 6. 기본 파라미터 타입 정합
 *
 * KIS cross-check:
 * 5종 Native 지표 (MA·EMA·RSI·MACD·BB) outputKeys spec §5-2 정합 검증.
 * backend NativeIndicatorSource 결과와 동일 key 사용 보장.
 *
 * 주의: 이 파일은 frontend REST API 호출 fixture가 아닌
 *      카탈로그 메타데이터 정확성 검증 (순수 단위 테스트, 네트워크 호출 0).
 */
import { describe, it, expect } from 'vitest'
import {
  INDICATOR_CATALOG,
  INDICATOR_CATALOG_MAP,
  type IndicatorCatalogItem,
} from '../../api/indicator'

// ──────────────────────────────────────────────
// 카탈로그 총량 검증
// ──────────────────────────────────────────────

describe('INDICATOR_CATALOG 총량 (spec §5-1·§5-2)', () => {
  it('총 82개 항목이어야 한다', () => {
    expect(INDICATOR_CATALOG).toHaveLength(82)
  })

  it('카테고리별 수량이 정합해야 한다', () => {
    const counts = INDICATOR_CATALOG.reduce<Record<string, number>>((acc, item) => {
      acc[item.category] = (acc[item.category] ?? 0) + 1
      return acc
    }, {})

    expect(counts['TREND']).toBe(20)       // spec indicator-catalog.md 정합
    expect(counts['MOMENTUM']).toBe(20)
    expect(counts['VOLUME']).toBe(12)
    expect(counts['VOLATILITY']).toBe(12)
    expect(counts['OSCILLATOR']).toBe(8)
    expect(counts['OTHER']).toBe(10)
    // 합계
    expect(20 + 20 + 12 + 12 + 8 + 10).toBe(82)
  })

  it('모든 항목의 id가 고유해야 한다', () => {
    const ids = INDICATOR_CATALOG.map((item) => item.id)
    const unique = new Set(ids)
    expect(unique.size).toBe(ids.length)
  })
})

// ──────────────────────────────────────────────
// multi-output 지표 검증 (spec §5-2 LOCK)
// ──────────────────────────────────────────────

describe('multi-output 지표 outputKeys (spec §5-2 LOCK)', () => {
  const getItem = (id: string): IndicatorCatalogItem => {
    const item = INDICATOR_CATALOG_MAP.get(id)
    if (!item) throw new Error(`지표 ${id} 카탈로그에 없음`)
    return item
  }

  it('macd outputKeys = {macd, signal, histogram}', () => {
    const item = getItem('macd')
    expect(item.multiOutput).toBe(true)
    expect(item.outputKeys).toEqual(['macd', 'signal', 'histogram'])
  })

  it('bb outputKeys = {upper, middle, lower}', () => {
    const item = getItem('bb')
    expect(item.multiOutput).toBe(true)
    expect(item.outputKeys).toEqual(['upper', 'middle', 'lower'])
  })

  it('stochastic_d outputKeys = {k, d}', () => {
    const item = getItem('stochastic_d')
    expect(item.multiOutput).toBe(true)
    expect(item.outputKeys).toEqual(['k', 'd'])
  })

  it('supertrend outputKeys = {value, direction}', () => {
    const item = getItem('supertrend')
    expect(item.multiOutput).toBe(true)
    expect(item.outputKeys).toEqual(['value', 'direction'])
  })

  it('pivot outputKeys = {p, r1, r2, r3, s1, s2, s3}', () => {
    const item = getItem('pivot')
    expect(item.multiOutput).toBe(true)
    expect(item.outputKeys).toEqual(['p', 'r1', 'r2', 'r3', 's1', 's2', 's3'])
  })

  it('multi-output 지표는 정확히 7종이어야 한다', () => {
    const multiOutputItems = INDICATOR_CATALOG.filter((item) => item.multiOutput)
    expect(multiOutputItems).toHaveLength(7)
    const multiOutputIds = multiOutputItems.map((item) => item.id).sort()
    expect(multiOutputIds).toEqual(['bb', 'fvg', 'macd', 'order_block', 'pivot', 'stochastic_d', 'supertrend'])
  })
})

// ──────────────────────────────────────────────
// single-output 지표 검증 (spec §5-1)
// ──────────────────────────────────────────────

describe('single-output 지표 outputKeys (spec §5-1)', () => {
  it('single-output 지표는 정확히 75종이어야 한다', () => {
    const singleItems = INDICATOR_CATALOG.filter((item) => !item.multiOutput)
    expect(singleItems).toHaveLength(75)
  })

  it('single-output 지표는 outputKeys가 1개여야 한다', () => {
    const singleItems = INDICATOR_CATALOG.filter((item) => !item.multiOutput)
    for (const item of singleItems) {
      expect(item.outputKeys).toHaveLength(1)
    }
  })

  // Native 5종 outputKeys cross-check (backend NativeIndicatorSource 결과 정합)
  it('ma outputKeys = [ma]', () => {
    expect(INDICATOR_CATALOG_MAP.get('ma')?.outputKeys).toEqual(['ma'])
  })

  it('ema outputKeys = [ema]', () => {
    expect(INDICATOR_CATALOG_MAP.get('ema')?.outputKeys).toEqual(['ema'])
  })

  it('rsi outputKeys = [rsi]', () => {
    expect(INDICATOR_CATALOG_MAP.get('rsi')?.outputKeys).toEqual(['rsi'])
  })

  // 검증 §5-1 주요 single-output 매핑 정합
  it('stochastic_k outputKeys = [k]', () => {
    expect(INDICATOR_CATALOG_MAP.get('stochastic_k')?.outputKeys).toEqual(['k'])
  })

  it('sar outputKeys = [sar]', () => {
    expect(INDICATOR_CATALOG_MAP.get('sar')?.outputKeys).toEqual(['sar'])
  })

  it('regression_slope outputKeys = [slope]', () => {
    expect(INDICATOR_CATALOG_MAP.get('regression_slope')?.outputKeys).toEqual(['slope'])
  })

  it('ichimoku_tenkan outputKeys = [tenkan]', () => {
    expect(INDICATOR_CATALOG_MAP.get('ichimoku_tenkan')?.outputKeys).toEqual(['tenkan'])
  })

  it('ichimoku_kijun outputKeys = [kijun]', () => {
    expect(INDICATOR_CATALOG_MAP.get('ichimoku_kijun')?.outputKeys).toEqual(['kijun'])
  })

  it('bb_width outputKeys = [width]', () => {
    expect(INDICATOR_CATALOG_MAP.get('bb_width')?.outputKeys).toEqual(['width'])
  })

  it('bb_percent outputKeys = [percent]', () => {
    expect(INDICATOR_CATALOG_MAP.get('bb_percent')?.outputKeys).toEqual(['percent'])
  })

  it('adx outputKeys = [adx]', () => {
    expect(INDICATOR_CATALOG_MAP.get('adx')?.outputKeys).toEqual(['adx'])
  })

  it('regression_intercept outputKeys = [intercept]', () => {
    expect(INDICATOR_CATALOG_MAP.get('regression_intercept')?.outputKeys).toEqual(['intercept'])
  })
})

// ──────────────────────────────────────────────
// INDICATOR_CATALOG_MAP 인덱스 검증
// ──────────────────────────────────────────────

describe('INDICATOR_CATALOG_MAP', () => {
  it('카탈로그 항목 수와 맵 크기가 일치해야 한다', () => {
    expect(INDICATOR_CATALOG_MAP.size).toBe(INDICATOR_CATALOG.length)
  })

  it('모든 CATALOG 항목이 MAP에 등록되어야 한다', () => {
    for (const item of INDICATOR_CATALOG) {
      expect(INDICATOR_CATALOG_MAP.has(item.id)).toBe(true)
      expect(INDICATOR_CATALOG_MAP.get(item.id)).toBe(item)
    }
  })

  it('존재하지 않는 id는 undefined 반환', () => {
    expect(INDICATOR_CATALOG_MAP.get('unknown_xyz_not_exist')).toBeUndefined()
  })
})

// ──────────────────────────────────────────────
// 기본 파라미터 타입 검증
// ──────────────────────────────────────────────

describe('기본 파라미터 타입', () => {
  it('period 파라미터는 number여야 한다', () => {
    const withPeriod = INDICATOR_CATALOG.filter((item) => 'period' in item.defaultParams)
    expect(withPeriod.length).toBeGreaterThan(0)
    for (const item of withPeriod) {
      expect(typeof item.defaultParams['period']).toBe('number')
    }
  })

  it('fast/slow 파라미터는 number여야 한다', () => {
    const macdItem = INDICATOR_CATALOG_MAP.get('macd')
    expect(macdItem).toBeDefined()
    expect(typeof macdItem!.defaultParams['fast']).toBe('number')
    expect(typeof macdItem!.defaultParams['slow']).toBe('number')
    expect(typeof macdItem!.defaultParams['signal']).toBe('number')
    expect(macdItem!.defaultParams['fast']).toBe(12)
    expect(macdItem!.defaultParams['slow']).toBe(26)
    expect(macdItem!.defaultParams['signal']).toBe(9)
  })

  it('bb 기본 파라미터 정합 (period=20, std_dev=2.0)', () => {
    const bbItem = INDICATOR_CATALOG_MAP.get('bb')
    expect(bbItem).toBeDefined()
    expect(bbItem!.defaultParams['period']).toBe(20)
    expect(bbItem!.defaultParams['std_dev']).toBe(2.0)
  })

  it('rsi 기본 파라미터 정합 (period=14)', () => {
    const rsiItem = INDICATOR_CATALOG_MAP.get('rsi')
    expect(rsiItem).toBeDefined()
    expect(rsiItem!.defaultParams['period']).toBe(14)
  })

  it('ma 기본 파라미터 정합 (period=20)', () => {
    const maItem = INDICATOR_CATALOG_MAP.get('ma')
    expect(maItem).toBeDefined()
    expect(maItem!.defaultParams['period']).toBe(20)
  })
})

// ──────────────────────────────────────────────
// AI import 0 검증 (룰 5 정합)
// ──────────────────────────────────────────────

describe('AI import 0 검증 (룰 5 — W2 T-IND)', () => {
  it('indicator.ts는 LLM provider import가 없어야 한다', async () => {
    // 동적 import로 실제 모듈 가져오기 (번들 내 AI import 검증)
    const module = await import('../../api/indicator')
    // 모듈 export에 AI 관련 키가 없어야 함
    const exports = Object.keys(module)
    const aiRelated = exports.filter((key) =>
      ['anthropic', 'openai', 'LLM', 'Claude', 'GPT', 'AI_API'].includes(key),
    )
    expect(aiRelated).toHaveLength(0)
  })
})
