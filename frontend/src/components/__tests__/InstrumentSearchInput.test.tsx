/**
 * InstrumentSearchInput smoke test (T-IM1 spec §I frontend 1건 — 룰 4 정합)
 *
 * useInstrumentSearch mock → dropdown 렌더링 + onSelect callback 검증.
 *
 * 검증 1: 검색어 입력 → dropdown에 삼성전자·삼성전기 표시
 * 검증 2: 삼성전자 클릭 → onSelect(code="005930") callback 호출
 */
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { InstrumentSearchInput } from '../InstrumentSearchInput'

// useInstrumentSearch hook mock
vi.mock('../../api/hooks/useInstrumentSearch', () => ({
  useInstrumentSearch: vi.fn(),
}))

import { useInstrumentSearch } from '../../api/hooks/useInstrumentSearch'

const mockResults = [
  { code: '005930', name: '삼성전자', market: 'KOSPI', sector: '전기전자', score: 1.0 },
  { code: '009150', name: '삼성전기', market: 'KOSPI', sector: '전기전자', score: 0.95 },
]
const wonikResult = { code: '240810', name: '원익IPS', market: 'KOSDAQ', sector: '반도체', score: 1.0 }

describe('InstrumentSearchInput', () => {
  const onSelect = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    // 기본: 검색 결과 없음
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [],
      isLoading: false,
      isError: false,
      isServerError: false,
    })
  })

  it('검색어 입력 후 dropdown에 삼성전자·삼성전기 표시', async () => {
    // mock: 삼성전 검색 시 2건 반환
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: mockResults,
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    render(<InstrumentSearchInput value="" onSelect={onSelect} />)

    const input = screen.getByRole('combobox', { name: '종목 검색' })

    // 검색어 입력 (debounce 없이 직접 상태 변경)
    await userEvent.type(input, '삼성전')

    // dropdown 항목 확인
    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
      expect(screen.getByText('삼성전기')).toBeInTheDocument()
    })
  })

  it('삼성전자 클릭 시 onSelect 콜백 호출', async () => {
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: mockResults,
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    render(<InstrumentSearchInput value="" onSelect={onSelect} />)

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await userEvent.type(input, '삼성전')

    // dropdown에서 삼성전자 클릭
    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })

    const samsungOption = screen.getByText('삼성전자').closest('button')!
    fireEvent.click(samsungOption)

    // onSelect callback: code="005930" 확인
    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith(mockResults[0])
  })

  it('선택된 코드로 마운트되면 종목명 라벨만 표시하고 dropdown은 열지 않는다', async () => {
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [wonikResult],
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    render(<InstrumentSearchInput value="240810" onSelect={onSelect} />)

    await waitFor(() => {
      expect(screen.getByDisplayValue('원익IPS (240810)')).toBeInTheDocument()
    })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onSelect).not.toHaveBeenCalled()
  })

  it('검색 결과가 1개뿐이면 Enter로 바로 선택한다', async () => {
    vi.mocked(useInstrumentSearch).mockReturnValue({
      results: [mockResults[0]],
      isLoading: false,
      isError: false,
      isServerError: false,
    })

    render(<InstrumentSearchInput value="" onSelect={onSelect} />)

    const input = screen.getByRole('combobox', { name: '종목 검색' })
    await userEvent.type(input, '삼성전자')

    await waitFor(() => {
      expect(screen.getByText('삼성전자')).toBeInTheDocument()
    })
    await userEvent.keyboard('{Enter}')

    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith(mockResults[0])
  })
})
