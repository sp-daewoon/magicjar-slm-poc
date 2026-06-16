/**
 * SectorRankingPanel 단위 테스트 — T-MARKET R2 frontend.
 *
 * 검증:
 * - 빈 list graceful (mock 합성 0 정합)
 * - todayTop / longTop 탭 전환
 * - 섹터 클릭 → onSelectSector 콜백
 * - 거래대금 억/조 단위 변환
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SectorRankingPanel } from '../components/SectorRankingPanel'
import type { SectorRankingResponse } from '../../../api/market'

function baseRanking(overrides: Partial<SectorRankingResponse> = {}): SectorRankingResponse {
  return {
    todayTop: [
      {
        sectorCode: '0024',
        sectorName: '금융업',
        changeRate: 1.5,
        tradeValue: 12_345_678_900,
        score: 1.2,
        metric: 'RS=1.05',
      },
      {
        sectorCode: '0026',
        sectorName: '전기·전자',
        changeRate: 0.8,
        tradeValue: 9_876_543_210,
        score: 1.0,
        metric: 'RS=0.98',
      },
    ],
    longTop: [
      {
        sectorCode: '0027',
        sectorName: '서비스업',
        changeRate: 2.1,
        tradeValue: 5_432_109_876,
        score: 1.3,
        metric: 'RS=1.15 · fit=3/3',
      },
    ],
    periodsUsed: [5, 20, 60],
    rsPeriod: 60,
    isUserCustom: false,
    ...overrides,
  }
}

describe('SectorRankingPanel', () => {
  it('로딩 — skeleton 5개', () => {
    const { container } = render(<SectorRankingPanel ranking={undefined} isLoading />)
    const skel = container.querySelectorAll('.animate-pulse')
    expect(skel.length).toBe(5)
  })

  it('에러 — alert 한국어', () => {
    render(<SectorRankingPanel ranking={undefined} isError />)
    expect(screen.getByRole('alert')).toHaveTextContent(/오류가 발생/)
  })

  it('빈 list — graceful 안내', () => {
    render(<SectorRankingPanel ranking={baseRanking({ todayTop: [], longTop: [] })} />)
    expect(screen.getByRole('status')).toHaveTextContent(/수집되지 않았습니다/)
  })

  it('todayTop default 탭 — 2건 표시', () => {
    render(<SectorRankingPanel ranking={baseRanking()} />)
    expect(screen.getByText('금융업')).toBeInTheDocument()
    expect(screen.getByText('전기·전자')).toBeInTheDocument()
    // longTop은 보이지 않아야 함
    expect(screen.queryByText('서비스업')).not.toBeInTheDocument()
  })

  it('longTop 탭 전환 → 서비스업 표시', () => {
    render(<SectorRankingPanel ranking={baseRanking()} />)
    fireEvent.click(screen.getByRole('tab', { name: '장기 강세' }))
    expect(screen.getByText('서비스업')).toBeInTheDocument()
    expect(screen.queryByText('금융업')).not.toBeInTheDocument()
  })

  it('섹터 클릭 — onSelectSector 콜백 (code + name)', () => {
    const handle = vi.fn()
    render(<SectorRankingPanel ranking={baseRanking()} onSelectSector={handle} />)
    fireEvent.click(screen.getByLabelText(/금융업 선택/))
    expect(handle).toHaveBeenCalledWith('0024', '금융업')
  })

  it('거래대금 — 123억 단위 변환', () => {
    render(<SectorRankingPanel ranking={baseRanking()} />)
    // 12_345_678_900 / 10^8 = 123.45억 → "123억"
    expect(screen.getByText(/123억/)).toBeInTheDocument()
  })

  it('periodsUsed + rsPeriod echo 표시', () => {
    render(<SectorRankingPanel ranking={baseRanking()} />)
    expect(screen.getByText(/MA 5·20·60 · RS 60/)).toBeInTheDocument()
  })
})
