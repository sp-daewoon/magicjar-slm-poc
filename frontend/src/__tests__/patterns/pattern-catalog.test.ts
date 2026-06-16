/**
 * 57 패턴 카탈로그 정확성 fixture 테스트
 *
 * T-PAT PR-40 R3 — 57 패턴 카탈로그 정합 검증 (frontend 측)
 *
 * 검증 항목:
 * 1. PATTERN_CATALOG 총 57개 항목 존재 (pattern-catalog.md KIS LOCK)
 * 2. 그룹별 수량 (Single=14, Double=16, Triple=27)
 * 3. R1 active 10건 id LOCK
 * 4. PATTERN_CATALOG_MAP 인덱스 정합
 * 5. PATTERN_R1_ACTIVE_IDS Set 정합
 * 6. 각 항목 필드 타입 정합
 * 7. defaultDirection 분포 검증
 *
 * 주의: 네트워크 호출 0 — 순수 메타데이터 정합 검증
 */
import { describe, it, expect } from 'vitest'
import {
  PATTERN_CATALOG,
  PATTERN_CATALOG_MAP,
  PATTERN_R1_ACTIVE_IDS,
  type PatternCatalogItem,
} from '../../api/patterns'

// ──────────────────────────────────────────────
// 카탈로그 총량 검증
// ──────────────────────────────────────────────

describe('PATTERN_CATALOG 총량', () => {
  it('총 57개 항목 존재', () => {
    expect(PATTERN_CATALOG).toHaveLength(57)
  })

  it('Single 14개', () => {
    const singles = PATTERN_CATALOG.filter((p) => p.group === 'single')
    expect(singles).toHaveLength(14)
  })

  it('Double 16개', () => {
    const doubles = PATTERN_CATALOG.filter((p) => p.group === 'double')
    expect(doubles).toHaveLength(16)
  })

  it('Triple 27개', () => {
    const triples = PATTERN_CATALOG.filter((p) => p.group === 'triple')
    expect(triples).toHaveLength(27)
  })
})

// ──────────────────────────────────────────────
// R1 active 검증
// ──────────────────────────────────────────────

describe('R1 active 10건 LOCK', () => {
  it('active=true 항목이 정확히 10개', () => {
    const actives = PATTERN_CATALOG.filter((p) => p.active)
    expect(actives).toHaveLength(10)
  })

  it('R1 active 10건 id LOCK — pattern-catalog.md KIS LOCK 정합', () => {
    const expectedActiveIds = [
      'doji',
      'hammer',
      'hanging_man',
      'inverted_hammer',
      'shooting_star',
      'engulfing',
      'morning_star',
      'evening_star',
      'three_white_soldiers',
      'three_black_crows',
    ]
    const actualActiveIds = PATTERN_CATALOG.filter((p) => p.active).map((p) => p.id).sort()
    expect(actualActiveIds).toEqual([...expectedActiveIds].sort())
  })

  it('PATTERN_R1_ACTIVE_IDS Set이 active=true 항목과 일치', () => {
    const activeFromCatalog = new Set(PATTERN_CATALOG.filter((p) => p.active).map((p) => p.id))
    expect(PATTERN_R1_ACTIVE_IDS.size).toBe(activeFromCatalog.size)
    for (const id of activeFromCatalog) {
      expect(PATTERN_R1_ACTIVE_IDS.has(id)).toBe(true)
    }
  })

  it('R2 stub 47건 active=false', () => {
    const stubs = PATTERN_CATALOG.filter((p) => !p.active)
    expect(stubs).toHaveLength(47)
  })
})

// ──────────────────────────────────────────────
// PATTERN_CATALOG_MAP 인덱스 검증
// ──────────────────────────────────────────────

describe('PATTERN_CATALOG_MAP', () => {
  it('Map 크기가 57', () => {
    expect(PATTERN_CATALOG_MAP.size).toBe(57)
  })

  it('모든 카탈로그 id가 Map에 존재', () => {
    for (const item of PATTERN_CATALOG) {
      expect(PATTERN_CATALOG_MAP.has(item.id)).toBe(true)
      expect(PATTERN_CATALOG_MAP.get(item.id)).toEqual(item)
    }
  })

  it('hammer 항목 조회', () => {
    const hammer = PATTERN_CATALOG_MAP.get('hammer')
    expect(hammer).toBeDefined()
    expect(hammer?.nameKo).toBe('해머')
    expect(hammer?.defaultDirection).toBe('BULL')
    expect(hammer?.active).toBe(true)
    expect(hammer?.group).toBe('single')
  })

  it('three_black_crows 항목 조회', () => {
    const item = PATTERN_CATALOG_MAP.get('three_black_crows')
    expect(item).toBeDefined()
    expect(item?.nameKo).toBe('세 까마귀')
    expect(item?.defaultDirection).toBe('BEAR')
    expect(item?.active).toBe(true)
    expect(item?.group).toBe('triple')
  })
})

// ──────────────────────────────────────────────
// 필드 타입 정합 검증
// ──────────────────────────────────────────────

describe('항목 필드 타입 정합', () => {
  it('모든 항목이 필수 필드를 보유', () => {
    for (const item of PATTERN_CATALOG) {
      const typedItem = item as PatternCatalogItem
      expect(typeof typedItem.id).toBe('string')
      expect(typeof typedItem.nameEn).toBe('string')
      expect(typeof typedItem.nameKo).toBe('string')
      expect(['BULLISH_REVERSAL', 'BEARISH_REVERSAL', 'BULLISH_CONTINUATION',
        'BEARISH_CONTINUATION', 'REVERSAL_DIRECTIONAL', 'INDECISION']).toContain(typedItem.category)
      expect(['single', 'double', 'triple']).toContain(typedItem.group)
      expect(['BULL', 'BEAR', 'NEUTRAL']).toContain(typedItem.defaultDirection)
      expect(typeof typedItem.active).toBe('boolean')
    }
  })

  it('모든 id가 고유', () => {
    const ids = PATTERN_CATALOG.map((p) => p.id)
    const uniqueIds = new Set(ids)
    expect(uniqueIds.size).toBe(ids.length)
  })

  it('id에 공백 없음', () => {
    for (const item of PATTERN_CATALOG) {
      expect(item.id).not.toMatch(/\s/)
      // 소문자 + 언더스코어 형식
      expect(item.id).toMatch(/^[a-z_]+$/)
    }
  })
})

// ──────────────────────────────────────────────
// defaultDirection 분포 검증
// ──────────────────────────────────────────────

describe('defaultDirection 분포', () => {
  it('BULL/BEAR/NEUTRAL 모두 존재', () => {
    const directions = new Set(PATTERN_CATALOG.map((p) => p.defaultDirection))
    expect(directions.has('BULL')).toBe(true)
    expect(directions.has('BEAR')).toBe(true)
    expect(directions.has('NEUTRAL')).toBe(true)
  })

  it('BULLISH_REVERSAL 카테고리는 BULL 방향', () => {
    const bullishReversal = PATTERN_CATALOG.filter((p) => p.category === 'BULLISH_REVERSAL')
    for (const item of bullishReversal) {
      expect(item.defaultDirection).toBe('BULL')
    }
  })

  it('BEARISH_REVERSAL 카테고리는 BEAR 방향', () => {
    const bearishReversal = PATTERN_CATALOG.filter((p) => p.category === 'BEARISH_REVERSAL')
    for (const item of bearishReversal) {
      expect(item.defaultDirection).toBe('BEAR')
    }
  })
})
