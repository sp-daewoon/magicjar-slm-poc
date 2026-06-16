/**
 * api/market.ts numeric coercion 단위 테스트 — T-MARKET R2 frontend.
 *
 * BigDecimal 직렬화는 string|number 둘 다 가능 (backend Jackson 구성) — 양쪽 호환.
 */
import { describe, it, expect } from 'vitest'
import {
  toNumberOrNull,
  DEFAULT_INDEX_CODES,
  INDEX_LABELS,
} from '../../../api/market'

describe('toNumberOrNull', () => {
  it('null → null', () => {
    expect(toNumberOrNull(null)).toBeNull()
  })

  it('undefined → null', () => {
    expect(toNumberOrNull(undefined)).toBeNull()
  })

  it('number 그대로', () => {
    expect(toNumberOrNull(123.45)).toBe(123.45)
  })

  it('숫자 string 변환', () => {
    expect(toNumberOrNull('2670.12')).toBe(2670.12)
  })

  it('잘못된 string → null', () => {
    expect(toNumberOrNull('abc')).toBeNull()
  })

  it('Infinity → null', () => {
    expect(toNumberOrNull('Infinity')).toBeNull()
  })

  it('0 그대로', () => {
    expect(toNumberOrNull(0)).toBe(0)
  })
})

describe('DEFAULT_INDEX_CODES + INDEX_LABELS', () => {
  it('4 default index LOCK', () => {
    expect(DEFAULT_INDEX_CODES).toEqual(['0001', '1001', '.IXIC', '.SPX'])
  })

  it('한국어 라벨 — KOSPI·KOSDAQ·NASDAQ·S&P 500', () => {
    expect(INDEX_LABELS['0001']).toBe('KOSPI')
    expect(INDEX_LABELS['1001']).toBe('KOSDAQ')
    expect(INDEX_LABELS['.IXIC']).toBe('NASDAQ')
    expect(INDEX_LABELS['.SPX']).toBe('S&P 500')
  })
})
