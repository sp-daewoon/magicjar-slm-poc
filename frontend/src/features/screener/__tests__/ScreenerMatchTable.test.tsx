/**
 * ScreenerMatchTable 단위 테스트 — 3 케이스
 *
 * spec §2-4 + ADR 0071 §9-4 정합:
 *   1. rank 정렬 (기본 오름차순)
 *   2. score 정렬 (클릭 시 내림차순)
 *   3. empty list — "매칭된 종목이 없습니다" 표시
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { ScreenerMatchTable } from '../components/ScreenerMatchTable'
import type { ScreenerMatchDto } from '../api/screenerApi'

const mockMatches: ScreenerMatchDto[] = [
  {
    rank: 1,
    instrumentCode: '005930',
    instrumentName: '삼성전자',
    score: 0.854,
    scoreComponents: { signal: 0.4, volume: 0.25, atr: 0.15, marketCap: 0.08 },
    reason: 'RSI(14) < 30',
  },
  {
    rank: 2,
    instrumentCode: '000660',
    instrumentName: 'SK하이닉스',
    score: 0.762,
    scoreComponents: { signal: 0.35, volume: 0.2, atr: 0.18, marketCap: 0.07 },
    reason: 'MACD bullish cross',
  },
  {
    rank: 3,
    instrumentCode: '035420',
    instrumentName: 'NAVER',
    score: 0.631,
    scoreComponents: { signal: 0.3, volume: 0.18, atr: 0.1, marketCap: 0.06 },
    reason: 'SMA 골든크로스',
  },
]

describe('ScreenerMatchTable', () => {
  it('1. rank 정렬 — 기본 1·2·3 순서', () => {
    render(
      <ScreenerMatchTable
        matches={mockMatches}
        selectedCodes={new Set()}
        onSelectionChange={vi.fn()}
      />,
    )

    const rows = screen.getAllByRole('row')
    // thead + 3 data rows
    expect(rows).toHaveLength(4)
    // 첫 번째 데이터 행에 삼성전자
    expect(rows[1]).toHaveTextContent('삼성전자')
    expect(rows[2]).toHaveTextContent('SK하이닉스')
    expect(rows[3]).toHaveTextContent('NAVER')
  })

  it('2. score 컬럼 클릭 → 내림차순 정렬', () => {
    render(
      <ScreenerMatchTable
        matches={mockMatches}
        selectedCodes={new Set()}
        onSelectionChange={vi.fn()}
      />,
    )

    // '점수' 헤더 클릭 (score 정렬 — sortKey !== 'score' → 내림차순 시작)
    const scoreHeader = screen.getByText('점수')
    fireEvent.click(scoreHeader)

    const rows = screen.getAllByRole('row')
    // score 내림차순: 삼성전자(0.854) > SK하이닉스(0.762) > NAVER(0.631)
    expect(rows[1]).toHaveTextContent('삼성전자')
    expect(rows[3]).toHaveTextContent('NAVER')
  })

  it('3. empty list — 안내 메시지 표시', () => {
    render(
      <ScreenerMatchTable
        matches={[]}
        selectedCodes={new Set()}
        onSelectionChange={vi.fn()}
      />,
    )

    expect(screen.getByText('매칭된 종목이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })
})
