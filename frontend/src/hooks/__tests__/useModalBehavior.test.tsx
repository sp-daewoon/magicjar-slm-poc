/**
 * useModalBehavior 단위 테스트
 *
 * 사용자 보고(2026-06-07) 결함 회귀 가드:
 *  - 팝업 열린 상태에서 배경 스크롤됨 → body overflow lock 검증
 *  - ESC로 안 닫힘 → Escape keydown → onClose 검증
 * 추가 안전성:
 *  - close 시 이전 overflow 값 복원
 *  - IME 조합 중(isComposing) ESC 무시
 *  - 중첩 2개 열고 하나 닫아도 lock 유지
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook, cleanup } from '@testing-library/react'
import { useModalBehavior } from '../useModalBehavior'

afterEach(() => {
  cleanup()
  document.body.style.overflow = ''
})

function dispatchEscape(init: KeyboardEventInit = {}) {
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', ...init }))
}

describe('useModalBehavior', () => {
  it('open=true 시 body overflow를 hidden으로 잠근다', () => {
    renderHook(() => useModalBehavior({ open: true, onClose: () => {} }))
    expect(document.body.style.overflow).toBe('hidden')
  })

  it('open=false 시에는 lock하지 않는다', () => {
    renderHook(() => useModalBehavior({ open: false, onClose: () => {} }))
    expect(document.body.style.overflow).toBe('')
  })

  it('unmount(close) 시 이전 overflow 값을 복원한다', () => {
    document.body.style.overflow = 'scroll'
    const { unmount } = renderHook(() => useModalBehavior({ open: true, onClose: () => {} }))
    expect(document.body.style.overflow).toBe('hidden')
    unmount()
    expect(document.body.style.overflow).toBe('scroll')
  })

  it('Escape keydown 시 onClose를 호출한다', () => {
    const onClose = vi.fn()
    renderHook(() => useModalBehavior({ open: true, onClose }))
    dispatchEscape()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('open=false면 Escape를 무시한다', () => {
    const onClose = vi.fn()
    renderHook(() => useModalBehavior({ open: false, onClose }))
    dispatchEscape()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('IME 조합 중(isComposing) Escape는 무시한다', () => {
    const onClose = vi.fn()
    renderHook(() => useModalBehavior({ open: true, onClose }))
    // KeyboardEventInit.isComposing 으로 한글 조합 중 ESC 시뮬레이션
    dispatchEscape({ isComposing: true })
    expect(onClose).not.toHaveBeenCalled()
    // 조합 종료 후 ESC는 정상 닫힘
    dispatchEscape()
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('Escape 외 다른 키는 무시한다', () => {
    const onClose = vi.fn()
    renderHook(() => useModalBehavior({ open: true, onClose }))
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('중첩 2개 모달을 열고 하나만 닫아도 lock이 유지된다', () => {
    document.body.style.overflow = 'visible'
    const first = renderHook(() => useModalBehavior({ open: true, onClose: () => {} }))
    const second = renderHook(() => useModalBehavior({ open: true, onClose: () => {} }))
    expect(document.body.style.overflow).toBe('hidden')

    // 안쪽(나중에 연) 모달만 닫음 → 아직 1개 열려 있으므로 lock 유지
    second.unmount()
    expect(document.body.style.overflow).toBe('hidden')

    // 마지막 모달까지 닫으면 최초 값(visible) 복원
    first.unmount()
    expect(document.body.style.overflow).toBe('visible')
  })

  it('unmount 후 keydown 리스너가 제거되어 onClose가 더는 호출되지 않는다', () => {
    const onClose = vi.fn()
    const { unmount } = renderHook(() => useModalBehavior({ open: true, onClose }))
    unmount()
    dispatchEscape()
    expect(onClose).not.toHaveBeenCalled()
  })
})
