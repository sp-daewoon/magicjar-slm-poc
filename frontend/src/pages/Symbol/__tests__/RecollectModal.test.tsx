/**
 * RecollectModal 단위 테스트 (ADR 0120 §6 — 재수집 체크박스 2그룹)
 *
 * 검증:
 *   - 기본 상태: 1m 체크 + 1m 파생 전체 체크 / 1d 그룹 미체크
 *   - 1m 미체크면 1m 파생 비활성
 *   - submit payload: 선택 interval CSV + minuteRecentDays
 *   - 1d 체크 시 1d 롤업 파생(1wk/1mo/1y) 포함
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { RecollectModal } from '../RecollectModal'

describe('RecollectModal', () => {
  it('기본 상태 — 1m + 1m 파생 전체 / 1d 미체크 → submit 시 1m·3m~12h + minuteRecentDays', () => {
    const onSubmit = vi.fn()
    render(
      <RecollectModal code="005930" symbolName="삼성전자" isSubmitting={false} onClose={() => {}} onSubmit={onSubmit} />,
    )

    // 헤더에 종목명·코드 표시
    expect(screen.getByText(/삼성전자 \(005930\)/)).toBeInTheDocument()

    fireEvent.click(screen.getByText('재수집 시작'))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    const arg = onSubmit.mock.calls[0][0]
    // 1m 기준봉 + 합산 파생 8종
    expect(arg.intervals).toEqual(['1m', '3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h'])
    expect(arg.minuteRecentDays).toBe(30) // 기본 콤보 = 최근 30일
  })

  it('1m 미체크 → 1m 파생 비활성 + payload 에서 1m 그룹 제외', () => {
    const onSubmit = vi.fn()
    render(
      <RecollectModal code="005930" isSubmitting={false} onClose={() => {}} onSubmit={onSubmit} />,
    )

    // 1m 기준봉 체크 해제
    fireEvent.click(screen.getByLabelText('1분봉 기준봉 재수집'))
    // 1m 파생(3m)은 disabled
    expect(screen.getByLabelText('3m 합산 재생성')).toBeDisabled()

    // 1d 만 체크
    fireEvent.click(screen.getByLabelText('일봉 기준봉 재수집'))
    fireEvent.click(screen.getByText('재수집 시작'))

    const arg = onSubmit.mock.calls[0][0]
    // 1m 그룹 없음 → 1d + 1wk/1mo/1y
    expect(arg.intervals).toEqual(['1d', '1wk', '1mo', '1y'])
    // 1m 미체크 → minuteRecentDays undefined
    expect(arg.minuteRecentDays).toBeUndefined()
  })

  it('기준봉 0개 선택 → 재수집 시작 비활성 + 안내', () => {
    const onSubmit = vi.fn()
    render(
      <RecollectModal code="005930" isSubmitting={false} onClose={() => {}} onSubmit={onSubmit} />,
    )

    fireEvent.click(screen.getByLabelText('1분봉 기준봉 재수집')) // 1m 해제 (1d 도 기본 미체크)

    const submitBtn = screen.getByText('재수집 시작')
    expect(submitBtn).toBeDisabled()
    expect(screen.getByText(/기준봉\(1m 또는 1d\)을 1개 이상/)).toBeInTheDocument()
  })

  it('1m 기간 콤보 변경 → minuteRecentDays 반영', () => {
    const onSubmit = vi.fn()
    render(
      <RecollectModal code="005930" isSubmitting={false} onClose={() => {}} onSubmit={onSubmit} />,
    )

    fireEvent.change(screen.getByLabelText('1분봉 재수집 기간 (최근 거래일)'), { target: { value: '90' } })
    fireEvent.click(screen.getByText('재수집 시작'))

    expect(onSubmit.mock.calls[0][0].minuteRecentDays).toBe(90)
  })
})
