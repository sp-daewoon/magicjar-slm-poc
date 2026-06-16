/**
 * DetailCell 테스트 — hover 툴팁 지연 + 클릭 활성화.
 *
 * 검증:
 *  1. hover 즉시는 툴팁 없음, TOOLTIP_DELAY_MS 경과 후 툴팁 표시 (JSON pretty)
 *  2. mouseleave 시 툴팁 사라짐
 *  3. 클릭 시 onActivate 호출
 *  4. Enter 키로 onActivate 호출
 *  5. 빈 detail 은 placeholder
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { DetailCell, TOOLTIP_DELAY_MS } from '../DetailCell'

describe('DetailCell', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('hover 직후엔 툴팁 없음, 지연 경과 후 JSON pretty 툴팁 표시', () => {
    render(
      <DetailCell detail='{"signalId":958,"strategyId":86}' onActivate={() => {}} />,
    )
    const cell = screen.getByRole('button', { name: '상세 보기' })

    fireEvent.mouseEnter(cell)
    // 지연 전엔 툴팁 없음
    expect(screen.queryByRole('tooltip')).toBeNull()

    act(() => {
      vi.advanceTimersByTime(TOOLTIP_DELAY_MS)
    })
    const tooltip = screen.getByRole('tooltip')
    // pretty-print 된 멀티라인 JSON
    expect(tooltip.textContent).toContain('"signalId": 958')
    expect(tooltip.textContent).toContain('\n')
  })

  it('지연 도중 mouseleave 하면 툴팁이 안 뜬다', () => {
    render(<DetailCell detail='{"a":1}' onActivate={() => {}} />)
    const cell = screen.getByRole('button', { name: '상세 보기' })
    fireEvent.mouseEnter(cell)
    act(() => {
      vi.advanceTimersByTime(TOOLTIP_DELAY_MS - 100)
    })
    fireEvent.mouseLeave(cell)
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(screen.queryByRole('tooltip')).toBeNull()
  })

  it('클릭 시 onActivate 호출', () => {
    const onActivate = vi.fn()
    render(<DetailCell detail='{"a":1}' onActivate={onActivate} />)
    fireEvent.click(screen.getByRole('button', { name: '상세 보기' }))
    expect(onActivate).toHaveBeenCalledTimes(1)
  })

  it('Enter 키로 onActivate 호출', () => {
    const onActivate = vi.fn()
    render(<DetailCell detail='{"a":1}' onActivate={onActivate} />)
    fireEvent.keyDown(screen.getByRole('button', { name: '상세 보기' }), {
      key: 'Enter',
    })
    expect(onActivate).toHaveBeenCalledTimes(1)
  })

  it('빈 detail 은 placeholder 표시 + button role 없음', () => {
    render(<DetailCell detail="" onActivate={() => {}} emptyLabel="(없음)" />)
    expect(screen.getByText('(없음)')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '상세 보기' })).toBeNull()
  })
})
