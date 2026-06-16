/**
 * JournalEntryList 단위 테스트 — 3 케이스
 *
 * ADR 0072 §9-4 + spec §3-3 정합:
 *   1. loading state — 스켈레톤 role="status"
 *   2. entries render — 매수/매도 label + 종목명 + 요약 표시
 *   3. empty state — "매매일지가 비어있습니다" 표시 (mock 합성 0 LOCK)
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { JournalEntryList } from '../components/JournalEntryList'
import type { TradingJournalEntryDto } from '../api/journalApi'

const mockEntries: TradingJournalEntryDto[] = [
  {
    id: 1001,
    eventType: 'ORDER_FILLED',
    instrumentCode: '005930',
    instrumentName: '삼성전자',
    occurredAt: '2026-05-14T14:32:18+09:00',
    summary: '체결: 삼성전자 매수 100주 @ 75,000원',
    realizedPnl: '150000.00',
    payload: { side: 'BUY', qty: 100, price: 75000 },
    notes: [],
  },
  {
    id: 1002,
    eventType: 'ORDER_FILLED',
    instrumentCode: '000660',
    instrumentName: 'SK하이닉스',
    occurredAt: '2026-05-14T10:15:00+09:00',
    summary: '체결: SK하이닉스 매도 10주 @ 150,000원 · 실현손익 +20,000원',
    realizedPnl: '20000.00',
    payload: { side: 'SELL', executedQty: 10, executedPrice: 150000 },
    notes: [{ id: 501, noteText: '확인', createdAt: '2026-05-14T10:20:00+09:00', updatedAt: '2026-05-14T10:20:00+09:00' }],
  },
  {
    id: 1003,
    eventType: 'ORDER_FILLED',
    instrumentCode: '038110',
    instrumentName: null,
    occurredAt: '2026-05-14T09:15:00+09:00',
    summary: '체결: 038110 매수 1주 @ 1,000원',
    realizedPnl: null,
    payload: { side: 'BUY', executedQty: 1, executedPrice: 1000 },
    notes: [],
  },
]

describe('JournalEntryList', () => {
  it('1. loading state — 스켈레톤 표시', () => {
    render(
      <JournalEntryList
        entries={[]}
        isLoading={true}
        isError={false}
        page={0}
        totalPages={0}
        onPageChange={vi.fn()}
        onEntryClick={vi.fn()}
      />,
    )
    const status = screen.getByRole('status', { name: '로딩 중' })
    expect(status).toBeInTheDocument()
  })

  it('2. entries render — 매수 매도 label + 종목명 + 요약 표시', () => {
    render(
      <JournalEntryList
        entries={mockEntries}
        isLoading={false}
        isError={false}
        page={0}
        totalPages={1}
        onPageChange={vi.fn()}
        onEntryClick={vi.fn()}
      />,
    )
    // 체결 구분 label
    expect(screen.getAllByText('매수')).toHaveLength(2)
    expect(screen.getByText('매도')).toBeInTheDocument()
    // 종목명
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('SK하이닉스')).toBeInTheDocument()
    expect(screen.getByText('038110')).toBeInTheDocument()
    expect(screen.queryByText('(038110)')).not.toBeInTheDocument()
    // 요약
    expect(screen.getByText('체결: 삼성전자 매수 100주 @ 75,000원')).toBeInTheDocument()
    // 메모 indicator
    expect(screen.getByText('1')).toBeInTheDocument()
  })

  it('3. empty state — "매매일지가 비어있습니다" 표시', () => {
    render(
      <JournalEntryList
        entries={[]}
        isLoading={false}
        isError={false}
        page={0}
        totalPages={0}
        onPageChange={vi.fn()}
        onEntryClick={vi.fn()}
      />,
    )
    expect(screen.getByText('매매일지가 비어있습니다.')).toBeInTheDocument()
  })

  it('4. 행 클릭 → onEntryClick 호출', () => {
    const onEntryClick = vi.fn()
    render(
      <JournalEntryList
        entries={mockEntries}
        isLoading={false}
        isError={false}
        page={0}
        totalPages={1}
        onPageChange={vi.fn()}
        onEntryClick={onEntryClick}
      />,
    )
    // 첫 번째 데이터 행 클릭 (tbody tr)
    const rows = screen.getAllByRole('row')
    // rows[0] = thead, rows[1] = first data row
    fireEvent.click(rows[1])
    expect(onEntryClick).toHaveBeenCalledWith(mockEntries[0])
  })
})
