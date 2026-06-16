/**
 * CandleCountSelector — 컴포넌트 단위 테스트 (Phase 4-F)
 *
 * ADR 0093 §결정 LOCK Phase 4-A·4-C 정합:
 *   - preset select 변경 시 onChange 호출
 *   - "직접입력" 선택 시 input 노출 + clamp 적용
 *   - max 초과 입력 → max로 자동 정정
 *   - interval 변경 시 stale value → max로 clamp 후 통보
 */
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { CandleCountSelector } from '../CandleCountSelector'

describe('CandleCountSelector', () => {
  it('preset 600 default 렌더링 + select·label 노출', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1d" onChange={onChange} />)

    expect(screen.getByText('표시 봉수')).toBeInTheDocument()
    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    expect(select.value).toBe('600')
  })

  it('preset 1200 선택 시 onChange(1200) 호출', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1d" onChange={onChange} />)

    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    fireEvent.change(select, { target: { value: '1200' } })

    expect(onChange).toHaveBeenCalledWith(1200)
  })

  it('"직접입력" 선택 시 input 노출', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1d" onChange={onChange} />)

    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'custom' } })

    expect(
      screen.getByLabelText(/차트 표시 봉수 직접입력 \(최소 1, 최대 5,000\)/),
    ).toBeInTheDocument()
  })

  it('직접입력 max 초과 → blur 시 max로 clamp (1d=5000)', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1d" onChange={onChange} />)

    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'custom' } })

    const input = screen.getByLabelText(/차트 표시 봉수 직접입력/) as HTMLInputElement
    fireEvent.change(input, { target: { value: '9999' } })
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(5000)
  })

  it('직접입력 음수 → blur 시 default 200 회귀 (ADR 0120 §6-2)', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1d" onChange={onChange} />)

    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'custom' } })

    const input = screen.getByLabelText(/차트 표시 봉수 직접입력/) as HTMLInputElement
    fireEvent.change(input, { target: { value: '-100' } })
    fireEvent.blur(input)

    expect(onChange).toHaveBeenCalledWith(200)
  })

  it('interval 변경 시 stale value(700)가 1y max(600) 초과 → 자동 clamp 통보', () => {
    const onChange = vi.fn()
    const { rerender } = render(
      <CandleCountSelector value={700} interval="1d" onChange={onChange} />,
    )
    // 1d에서는 700이 max 5000 이하 → onChange 호출 없음
    onChange.mockClear()

    rerender(<CandleCountSelector value={700} interval="1y" onChange={onChange} />)
    // 1y max = 600 → 자동 clamp 600 통보
    expect(onChange).toHaveBeenCalledWith(600)
  })

  it('non-preset value (예: 700) 진입 시 직접입력 input 자동 노출', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={700} interval="1d" onChange={onChange} />)

    expect(screen.getByLabelText(/차트 표시 봉수 직접입력/)).toBeInTheDocument()
  })

  it('Enter 키 → blur 트리거 동등 clamp', () => {
    const onChange = vi.fn()
    render(<CandleCountSelector value={600} interval="1y" onChange={onChange} />)
    onChange.mockClear()

    const select = screen.getByLabelText('차트 표시 봉수 preset 선택') as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'custom' } })

    const input = screen.getByLabelText(/차트 표시 봉수 직접입력/) as HTMLInputElement
    fireEvent.change(input, { target: { value: '1500' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    // 1y max=600 → clamp 600
    expect(onChange).toHaveBeenCalledWith(600)
  })
})
