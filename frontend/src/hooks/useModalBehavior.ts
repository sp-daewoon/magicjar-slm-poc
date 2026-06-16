import { useEffect } from 'react'

/**
 * useModalBehavior — 전역 모달 공용 동작 훅
 *
 * 사용자 보고 결함(2026-06-07): 팝업 열린 상태에서
 *   - 뒤 배경이 스크롤됨 (body scroll lock 누락)
 *   - ESC로 안 닫힘 (keydown 핸들러 누락)
 * 13개 모달이 ad-hoc 구현(scroll lock 2곳 · ESC 4곳)이라 일관성이 없었음.
 * ScreenerResultModal·SettingsModal 의 검증된 패턴을 흡수해 단일 훅으로 통일한다.
 *
 * 동작:
 *  - open=true 동안 document.body.style.overflow = 'hidden'
 *  - 중첩 모달 안전: 모듈 레벨 카운터로 마지막 모달이 닫힐 때만 원래 overflow 복원
 *  - Escape keydown → onClose (input/textarea 포커스 중에도 닫힘 허용)
 *  - IME 조합 중(e.isComposing / keyCode 229)에는 ESC 무시 — 한글 입력 중 오닫힘 방지
 *  - unmount / open=false 전환 시 cleanup 철저
 *
 * @param open    모달 표시 여부. false면 lock·ESC 모두 비활성.
 * @param onClose ESC 또는 외부 닫기 트리거 시 호출. 저장 confirm 등 onClose 의미는 호출 측이 보존.
 */

// 모듈 레벨 — 동시에 열린 모달 수와 첫 lock 직전 overflow 값을 추적.
let lockCount = 0
let previousBodyOverflow = ''

function acquireScrollLock(): void {
  if (typeof document === 'undefined') return
  if (lockCount === 0) {
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  }
  lockCount += 1
}

function releaseScrollLock(): void {
  if (typeof document === 'undefined') return
  if (lockCount === 0) return
  lockCount -= 1
  if (lockCount === 0) {
    document.body.style.overflow = previousBodyOverflow
    previousBodyOverflow = ''
  }
}

export interface UseModalBehaviorOptions {
  open: boolean
  onClose: () => void
}

export function useModalBehavior({ open, onClose }: UseModalBehaviorOptions): void {
  // body scroll lock (중첩 안전 카운터)
  useEffect(() => {
    if (!open) return
    acquireScrollLock()
    return () => {
      releaseScrollLock()
    }
  }, [open])

  // ESC 닫기 (IME 조합 중 무시)
  useEffect(() => {
    if (!open) return
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return
      // 한글 등 IME 조합 확정용 ESC는 모달 닫기로 처리하지 않음.
      if (e.isComposing || e.keyCode === 229) return
      onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])
}
