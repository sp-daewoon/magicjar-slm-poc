import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { JournalEntryFilterBar, type JournalEntryFilterState } from '../components/JournalEntryFilterBar'

const defaultFilter: JournalEntryFilterState = {
  from: '2026-06-06',
  to: '2026-05-30',
  instrumentCode: '',
  instrumentInput: '',
}

describe('JournalEntryFilterBar', () => {
  it('종목검색과 같은 텍스트필드와 검색 버튼으로 종목 검색어를 적용한다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<JournalEntryFilterBar filter={defaultFilter} onChange={onChange} onReset={vi.fn()} />)

    await user.type(screen.getByRole('searchbox', { name: '종목 검색' }), '삼성')
    expect(onChange).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onChange).toHaveBeenLastCalledWith({
      ...defaultFilter,
      instrumentCode: '',
      instrumentInput: '삼성',
    })
  })

  it('선택 라벨 형식이면 검색 버튼 클릭 시 6자리 코드 필터로 적용한다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    render(<JournalEntryFilterBar filter={defaultFilter} onChange={onChange} onReset={vi.fn()} />)

    await user.type(screen.getByRole('searchbox', { name: '종목 검색' }), '삼성전자 (005930)')
    await user.click(screen.getByRole('button', { name: '검색' }))

    expect(onChange).toHaveBeenLastCalledWith({
      ...defaultFilter,
      instrumentCode: '005930',
      instrumentInput: '삼성전자 (005930)',
    })
  })
})
