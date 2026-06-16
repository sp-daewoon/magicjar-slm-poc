/**
 * SystemEventDetailModal 테스트 — 메타 렌더 + JSON pretty + 복사.
 *
 * 검증:
 *  1. 이벤트 메타(시각·심각도·actor·action) 표시
 *  2. detail JSON pretty-print (멀티라인)
 *  3. 복사 버튼 → navigator.clipboard.writeText 호출 + "복사됨" 표시
 *  4. 닫기 버튼 → onClose
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { SystemEventDetailModal } from '../SystemEventDetailModal'
import type { SystemEvent } from '../../../api/system-event'

const EVENT: SystemEvent = {
  id: 42,
  category: 'RECONCILE',
  severity: 'WARN',
  actor: 'OrderReconciliationWorker',
  action: 'RECONCILE_FALLBACK_FIRED',
  userId: '74683223',
  correlationId: 'abc-123',
  detailJson: '{"signalId":958,"strategyId":86}',
  occurredAt: '2026-06-12T05:00:00Z',
  viewedAt: null,
  resolvedAt: null,
}

describe('SystemEventDetailModal', () => {
  beforeEach(() => {
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    })
  })

  it('메타와 pretty JSON detail 을 렌더한다', () => {
    render(<SystemEventDetailModal event={EVENT} onClose={() => {}} />)
    expect(screen.getByText('시스템 이벤트 상세')).toBeInTheDocument()
    expect(screen.getByText('RECONCILE_FALLBACK_FIRED')).toBeInTheDocument()
    expect(screen.getByText('OrderReconciliationWorker')).toBeInTheDocument()

    const pre = screen.getByTestId('system-event-detail-pre')
    expect(pre.textContent).toContain('"signalId": 958')
    expect(pre.textContent).toContain('\n')
  })

  it('복사 버튼 클릭 시 clipboard 에 전체 평문 기록 + 복사됨 표시', async () => {
    render(<SystemEventDetailModal event={EVENT} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: '전체 복사' }))

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1)
    })
    const payload = (navigator.clipboard.writeText as ReturnType<typeof vi.fn>).mock
      .calls[0][0] as string
    expect(payload).toContain('[시스템 이벤트 #42]')
    expect(payload).toContain('RECONCILE_FALLBACK_FIRED')
    expect(payload).toContain('"signalId": 958')

    await waitFor(() => {
      expect(screen.getByText('✓ 복사됨')).toBeInTheDocument()
    })
  })

  it('푸터 닫기 버튼 클릭 시 onClose 호출', () => {
    const onClose = vi.fn()
    render(<SystemEventDetailModal event={EVENT} onClose={onClose} />)
    // 헤더 X(aria-label) + 푸터(text) 둘 다 "닫기" accessible name → 푸터 버튼 특정
    const closeButtons = screen.getAllByRole('button', { name: '닫기' })
    const footerButton = closeButtons.find((b) => b.textContent === '닫기')
    expect(footerButton).toBeDefined()
    fireEvent.click(footerButton!)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('ESC 키로 onClose 호출 (useModalBehavior)', () => {
    const onClose = vi.fn()
    render(<SystemEventDetailModal event={EVENT} onClose={onClose} />)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
