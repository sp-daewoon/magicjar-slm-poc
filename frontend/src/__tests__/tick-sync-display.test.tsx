/**
 * tick-sync-display 단위 테스트 — dogfooding hotfix
 *
 * 검증 대상:
 * 1. TickPayload 신규 필드 (priceChange·changeRate·changeSign) 타입 정합
 * 2. changeSignToArrow: KIS 부호 코드 → 방향 문자열 변환
 * 3. changeSignToColorClass: KIS 부호 코드 → 한국식 색상 클래스
 * 4. SymbolPriceCard: STOMP tick changeRate·priceChange·changeSign 우선 표시
 * 5. SymbolPriceCard: changeRate null 시 REST fallback 동작
 * 6. 현재가·등락률·등락가 동일 tick source에서 동시 갱신 (싱크 검증)
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { SymbolDto, TickPayload } from '../api/symbol'
import { changeSignToArrow, changeSignToColorClass, SymbolPriceCard } from '../pages/Symbol/SymbolPriceCard'

// ──────────────────────────────────────────────
// marketHours mock (테스트 환경에서 장 시간 고정)
// ──────────────────────────────────────────────
vi.mock('../lib/marketHours', () => ({
  getPriceBadge: () => 'live',
}))

// ──────────────────────────────────────────────
// 테스트 픽스처
// ──────────────────────────────────────────────

const baseSymbol: SymbolDto = {
  code: '005930',
  name: '삼성전자',
  market: 'KOSPI',
  currentPrice: '60900',
  previousClose: '60500',
  change: '400',
  changeRate: '0.66',
  high: '61000',
  low: '60750',
  open: '60800',
  volume: 12345678,
  asOf: '2026-05-11T11:00:00.000Z',
  source: 'live',
  stale: false,
}

/** STOMP tick — 신규 필드 모두 포함 (상승 case) */
const tickWithChange: TickPayload = {
  code: '005930',
  price: '61500',
  priceChange: '1000',
  changeRate: '1.65',
  changeSign: '2', // 상승
  volume: 100,
  ts: '2026-05-11T11:10:00.000Z',
  source: 'live',
  stale: false,
}

/** STOMP tick — 신규 필드 null (polling source) */
const tickNoChange: TickPayload = {
  code: '005930',
  price: '61200',
  priceChange: null,
  changeRate: null,
  changeSign: null,
  volume: 50,
  ts: '2026-05-11T11:05:00.000Z',
  source: 'polling',
  stale: false,
}

/** STOMP tick — 하락 case */
const tickDown: TickPayload = {
  code: '005930',
  price: '60100',
  priceChange: '-400',
  changeRate: '-0.66',
  changeSign: '5', // 하락
  volume: 200,
  ts: '2026-05-11T11:15:00.000Z',
  source: 'live',
  stale: false,
}

/** STOMP tick — 보합 case */
const tickFlat: TickPayload = {
  code: '005930',
  price: '60500',
  priceChange: '0',
  changeRate: '0.00',
  changeSign: '3', // 보합
  volume: 10,
  ts: '2026-05-11T11:20:00.000Z',
  source: 'live',
  stale: false,
}

function renderPriceCard(tick?: TickPayload | null) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <SymbolPriceCard symbol={baseSymbol} realtimeTick={tick} />
    </QueryClientProvider>,
  )
}

// ──────────────────────────────────────────────
// 1. changeSignToArrow 변환 검증
// ──────────────────────────────────────────────

describe('changeSignToArrow', () => {
  it('"1"(상한) → "▲"', () => {
    expect(changeSignToArrow('1')).toBe('▲')
  })

  it('"2"(상승) → "▲"', () => {
    expect(changeSignToArrow('2')).toBe('▲')
  })

  it('"3"(보합) → "·"', () => {
    expect(changeSignToArrow('3')).toBe('·')
  })

  it('"4"(하한) → "▼"', () => {
    expect(changeSignToArrow('4')).toBe('▼')
  })

  it('"5"(하락) → "▼"', () => {
    expect(changeSignToArrow('5')).toBe('▼')
  })

  it('null → ""', () => {
    expect(changeSignToArrow(null)).toBe('')
  })

  it('undefined → ""', () => {
    expect(changeSignToArrow(undefined)).toBe('')
  })
})

// ──────────────────────────────────────────────
// 2. changeSignToColorClass 검증
// ──────────────────────────────────────────────

describe('changeSignToColorClass', () => {
  it('"1"(상한) → text-red-400', () => {
    expect(changeSignToColorClass('1')).toBe('text-red-400')
  })

  it('"2"(상승) → text-red-400', () => {
    expect(changeSignToColorClass('2')).toBe('text-red-400')
  })

  it('"3"(보합) → text-gray-300', () => {
    expect(changeSignToColorClass('3')).toBe('text-gray-300')
  })

  it('"4"(하한) → text-blue-400', () => {
    expect(changeSignToColorClass('4')).toBe('text-blue-400')
  })

  it('"5"(하락) → text-blue-400', () => {
    expect(changeSignToColorClass('5')).toBe('text-blue-400')
  })

  it('null → text-gray-300', () => {
    expect(changeSignToColorClass(null)).toBe('text-gray-300')
  })
})

// ──────────────────────────────────────────────
// 3. SymbolPriceCard: STOMP source 우선 표시 검증
// ──────────────────────────────────────────────

describe('SymbolPriceCard — STOMP source 우선', () => {
  it('STOMP tick(상승) 도착 시 현재가·등락가·등락률이 tick 값으로 동기 표시된다', () => {
    renderPriceCard(tickWithChange)

    // 현재가: tick.price = 61500
    expect(screen.getByText('61,500')).toBeInTheDocument()

    // 등락가: tick.priceChange = +1000
    expect(screen.getByText('+1,000')).toBeInTheDocument()

    // 등락률: tick.changeRate = +1.65%
    expect(screen.getByText('+1.65%')).toBeInTheDocument()

    // 방향 부호: changeSign='2' → ▲
    expect(screen.getByText('▲')).toBeInTheDocument()
  })

  it('STOMP tick(하락) 도착 시 등락 표시가 하락값으로 갱신된다', () => {
    renderPriceCard(tickDown)

    // 현재가: tick.price = 60100
    expect(screen.getByText('60,100')).toBeInTheDocument()

    // 등락가: tick.priceChange = -400
    expect(screen.getByText('-400')).toBeInTheDocument()

    // 등락률: tick.changeRate = -0.66%
    expect(screen.getByText('-0.66%')).toBeInTheDocument()

    // 방향 부호: changeSign='5' → ▼
    expect(screen.getByText('▼')).toBeInTheDocument()
  })

  it('STOMP tick(보합) 도착 시 등락 표시가 보합값으로 갱신된다', () => {
    renderPriceCard(tickFlat)

    // 현재가: tick.price = 60500 (현재가 heading 영역에 표시)
    // note: 60500은 전일종가(previousClose=60500)와 동일 숫자이므로 getAllByText 사용
    const priceEls = screen.getAllByText('60,500')
    expect(priceEls.length).toBeGreaterThanOrEqual(1)

    // 등락가: tick.priceChange = +0
    expect(screen.getByText('+0')).toBeInTheDocument()

    // 등락률: tick.changeRate = +0.00%
    expect(screen.getByText('+0.00%')).toBeInTheDocument()

    // 방향 부호: changeSign='3' → ·
    expect(screen.getByText('·')).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// 4. SymbolPriceCard: null 필드 시 REST fallback 검증
// ──────────────────────────────────────────────

describe('SymbolPriceCard — null 필드 → REST fallback', () => {
  it('STOMP tick changeRate·priceChange가 null이면 REST SymbolDto 값으로 표시된다', () => {
    renderPriceCard(tickNoChange)

    // 현재가: tick.price = 61200 (price는 항상 있음)
    expect(screen.getByText('61,200')).toBeInTheDocument()

    // 등락가 fallback: symbol.change = +400
    expect(screen.getByText('+400')).toBeInTheDocument()

    // 등락률 fallback: symbol.changeRate = +0.66%
    expect(screen.getByText('+0.66%')).toBeInTheDocument()

    // 방향 부호: changeSign=null → 부호 미표시 (▲···▼ 없음)
    expect(screen.queryByText('▲')).not.toBeInTheDocument()
    expect(screen.queryByText('▼')).not.toBeInTheDocument()
    expect(screen.queryByText('·')).not.toBeInTheDocument()
  })

  it('STOMP tick 없으면 REST SymbolDto 초기값을 그대로 표시한다', () => {
    renderPriceCard(null)

    // 현재가: symbol.currentPrice = 60900
    expect(screen.getByText('60,900')).toBeInTheDocument()

    // 등락가: symbol.change = +400
    expect(screen.getByText('+400')).toBeInTheDocument()

    // 등락률: symbol.changeRate = +0.66%
    expect(screen.getByText('+0.66%')).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// 5. TickPayload 인터페이스 신규 필드 타입 정합 (컴파일 레벨 검증)
// ──────────────────────────────────────────────

describe('TickPayload 인터페이스 신규 필드 정합', () => {
  it('priceChange·changeRate·changeSign이 string | null 타입을 허용한다', () => {
    const fullTick: TickPayload = {
      code: '005930',
      price: '61000',
      priceChange: '500',
      changeRate: '0.83',
      changeSign: '2',
      volume: 100,
      ts: '2026-05-11T11:00:00Z',
      source: 'live',
      stale: false,
    }
    expect(fullTick.priceChange).toBe('500')
    expect(fullTick.changeRate).toBe('0.83')
    expect(fullTick.changeSign).toBe('2')
  })

  it('priceChange·changeRate·changeSign이 null을 허용한다', () => {
    const nullTick: TickPayload = {
      code: '005930',
      price: '61000',
      priceChange: null,
      changeRate: null,
      changeSign: null,
      volume: 100,
      ts: '2026-05-11T11:00:00Z',
      source: 'polling',
      stale: false,
    }
    expect(nullTick.priceChange).toBeNull()
    expect(nullTick.changeRate).toBeNull()
    expect(nullTick.changeSign).toBeNull()
  })
})
