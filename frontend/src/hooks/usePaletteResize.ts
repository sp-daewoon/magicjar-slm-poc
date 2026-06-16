/**
 * usePaletteResize — BlockPalette 좌측 패널 드래그 리사이즈 hook
 *
 * - min: 200px / max: 480px
 * - 드래그 중 cursor: col-resize (document-level)
 * - localStorage 영속: 페이지 재방문 시 폭 복원
 * - 외부 라이브러리 의존 0건
 */
import { useState, useRef, useCallback, useEffect } from 'react'

const STORAGE_KEY = 'magicjar:palette-width'
const DEFAULT_WIDTH = 224 // w-56 = 14rem = 224px
const MIN_WIDTH = 200
const MAX_WIDTH = 480

function clamp(v: number, min: number, max: number) {
  return Math.min(max, Math.max(min, v))
}

function readStoredWidth(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const n = parseInt(raw, 10)
      if (!isNaN(n)) return clamp(n, MIN_WIDTH, MAX_WIDTH)
    }
  } catch {
    // localStorage 접근 실패 시 default 사용
  }
  return DEFAULT_WIDTH
}

export interface UsePaletteResizeReturn {
  width: number
  /** ResizeHandle에 붙일 onMouseDown */
  handleMouseDown: (e: React.MouseEvent) => void
  /** 드래그 중 여부 (핸들 색상 피드백용) */
  isDragging: boolean
}

export function usePaletteResize(): UsePaletteResizeReturn {
  const [width, setWidth] = useState<number>(readStoredWidth)
  const [isDragging, setIsDragging] = useState(false)
  const startXRef = useRef(0)
  const startWidthRef = useRef(0)

  const handleMouseMove = useCallback((e: MouseEvent) => {
    const delta = e.clientX - startXRef.current
    const next = clamp(startWidthRef.current + delta, MIN_WIDTH, MAX_WIDTH)
    setWidth(next)
  }, [])

  const handleMouseUp = useCallback(() => {
    setIsDragging(false)
    document.documentElement.style.cursor = ''
    document.documentElement.style.userSelect = ''
    document.removeEventListener('mousemove', handleMouseMove)
    document.removeEventListener('mouseup', handleMouseUp)
    // 드래그 완료 시 localStorage 저장
    setWidth((w) => {
      try {
        localStorage.setItem(STORAGE_KEY, String(w))
      } catch {
        // ignore
      }
      return w
    })
  }, [handleMouseMove])

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault()
      startXRef.current = e.clientX
      startWidthRef.current = width
      setIsDragging(true)
      document.documentElement.style.cursor = 'col-resize'
      document.documentElement.style.userSelect = 'none'
      document.addEventListener('mousemove', handleMouseMove)
      document.addEventListener('mouseup', handleMouseUp)
    },
    [width, handleMouseMove, handleMouseUp],
  )

  // unmount 시 이벤트 정리
  useEffect(() => {
    return () => {
      document.removeEventListener('mousemove', handleMouseMove)
      document.removeEventListener('mouseup', handleMouseUp)
      document.documentElement.style.cursor = ''
      document.documentElement.style.userSelect = ''
    }
  }, [handleMouseMove, handleMouseUp])

  return { width, handleMouseDown, isDragging }
}
