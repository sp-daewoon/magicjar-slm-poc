/**
 * IndexTrendCard 단위 테스트 — T-MARKET R2 frontend.
 *
 * 검증:
 * - 한국어 라벨 (INDEX_LABELS 정합)
 * - dataInsufficient 분기 → "데이터 부족" 표시
 * - 양수/음수 changeRate 색상 분기
 * - onClick 콜백 + selected 상태
 * - isUserCustom → "user pref" 배지
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { IndexTrendCard } from '../components/IndexTrendCard'
import type { IndexTrendResponse } from '../../../api/market'

function baseTrend(overrides: Partial<IndexTrendResponse> = {}): IndexTrendResponse {
  return {
    code: '0001',
    ma: { '5': 2670.12, '20': 2685.34, '60': 2710.55 },
    crossSignal: 'NEUTRAL',
    trendLabel: 'UP',
    periodsUsed: [5, 20, 60],
    isUserCustom: false,
    dataInsufficient: false,
    lastClose: 2685.34,
    changeRate: 0.55,
    ...overrides,
  }
}

describe('IndexTrendCard', () => {
  it('정상 — KOSPI 라벨 + 종가 + 등락률 + 상승 배지', () => {
    render(<IndexTrendCard index={baseTrend()} />)
    expect(screen.getByText('KOSPI')).toBeInTheDocument()
    // 종가 — toLocaleString('ko-KR') 적용
    expect(screen.getByText(/2,685\.34/)).toBeInTheDocument()
    expect(screen.getByText(/\+0\.55%/)).toBeInTheDocument()
    expect(screen.getByLabelText(/추세 상승/)).toBeInTheDocument()
    // MA periods echo
    expect(screen.getByText(/MA 5·20·60/)).toBeInTheDocument()
  })

  it('dataInsufficient=true — "데이터 부족" 표시 + 캔들 안내', () => {
    render(<IndexTrendCard index={baseTrend({ dataInsufficient: true })} />)
    expect(screen.getByText(/데이터 부족/)).toBeInTheDocument()
    expect(screen.getByText(/60일 이상/)).toBeInTheDocument()
  })

  it('음수 changeRate — red text class', () => {
    render(<IndexTrendCard index={baseTrend({ changeRate: -1.25 })} />)
    const el = screen.getByText(/-1\.25%/)
    expect(el.className).toContain('red')
  })

  it('isUserCustom=true — "user pref" 배지', () => {
    render(<IndexTrendCard index={baseTrend({ isUserCustom: true })} />)
    expect(screen.getByText('user pref')).toBeInTheDocument()
  })

  it('onClick — 클릭 시 code 전달 + 버튼 렌더', () => {
    const handle = vi.fn()
    render(<IndexTrendCard index={baseTrend()} onClick={handle} />)
    const btn = screen.getByLabelText(/KOSPI 추세 카드/)
    expect(btn.tagName).toBe('BUTTON')
    fireEvent.click(btn)
    expect(handle).toHaveBeenCalledWith('0001')
  })

  it('selected=true — aria-pressed=true + blue border', () => {
    render(<IndexTrendCard index={baseTrend()} onClick={() => {}} selected />)
    const btn = screen.getByLabelText(/KOSPI 추세 카드/)
    expect(btn.getAttribute('aria-pressed')).toBe('true')
    expect(btn.className).toContain('blue-500')
  })

  it('알 수 없는 code → fallback 표시 (코드 자체)', () => {
    render(<IndexTrendCard index={baseTrend({ code: '9999' })} />)
    expect(screen.getByText('9999')).toBeInTheDocument()
  })
})
