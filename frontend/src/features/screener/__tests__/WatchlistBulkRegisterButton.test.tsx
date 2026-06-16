/**
 * WatchlistBulkRegisterButton 단위 테스트 — 3 케이스
 *
 * ADR 0071 §9-4 정합:
 *   1. 선택 0건 → 버튼 disabled
 *   2. 선택 N건 → 버튼 활성 + 텍스트 "Watchlist에 N개 등록"
 *   3. isPending=true → "등록 중..." 표시 + disabled
 */
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { WatchlistBulkRegisterButton } from '../components/WatchlistBulkRegisterButton'

describe('WatchlistBulkRegisterButton', () => {
  it('1. 선택 0건 → 버튼 disabled + 기본 텍스트', () => {
    render(
      <WatchlistBulkRegisterButton
        selectedCodes={new Set()}
        isPending={false}
        onRegister={vi.fn()}
      />,
    )

    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    expect(btn).toHaveTextContent('Watchlist 등록')
  })

  it('2. 선택 3건 → 버튼 활성 + "Watchlist에 3개 등록"', () => {
    const onRegister = vi.fn()
    render(
      <WatchlistBulkRegisterButton
        selectedCodes={new Set(['005930', '000660', '035420'])}
        isPending={false}
        onRegister={onRegister}
      />,
    )

    const btn = screen.getByRole('button')
    expect(btn).not.toBeDisabled()
    expect(btn).toHaveTextContent('Watchlist에 3개 등록')

    fireEvent.click(btn)
    expect(onRegister).toHaveBeenCalledTimes(1)
  })

  it('3. isPending=true → "등록 중..." + disabled', () => {
    render(
      <WatchlistBulkRegisterButton
        selectedCodes={new Set(['005930'])}
        isPending={true}
        onRegister={vi.fn()}
      />,
    )

    const btn = screen.getByRole('button')
    expect(btn).toBeDisabled()
    expect(btn).toHaveTextContent('등록 중...')
  })
})
