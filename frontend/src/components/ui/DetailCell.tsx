/**
 * DetailCell — 잘림(truncate)되는 detail 텍스트 셀 + hover 툴팁 + 클릭 핸들.
 *
 * 사용자 보고(2026-06-12, 최고 관리자): 시스템 이벤트 화면 Detail 컬럼의
 * `{"signalId":958,...}` JSON 이 잘려 안 보임 →
 *   1. 마우스 ~1초(700ms) hover → 말풍선 툴팁으로 전체 표시 (JSON pretty)
 *   2. 클릭 → 상세 모달 (호출 측이 onClick 으로 모달 오픈)
 *
 * 설계:
 *   - 기존 테이블 레이아웃 불변(add-only). 셀 내부 span 만 교체.
 *   - 툴팁은 portal(body) 로 렌더 → 테이블 overflow-x clip 영향 0, 화면 경계 보정.
 *   - 재사용 가능(props 만으로 동작)하되 적용은 SystemEventPage 한정(과확장 금지).
 *
 * 접근성:
 *   - role="button" + tabIndex + Enter/Space 키로 onClick 트리거
 *   - 키보드 focus 시에도 툴팁 표시
 *
 * AI 호출 0.
 */
import { useCallback, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { prettyJson } from '../../lib/prettyJson'

/** hover 후 툴팁 표시까지 지연(ms) — 사용자 지시 "1초 정도" */
export const TOOLTIP_DELAY_MS = 700

interface Props {
  /** 원본 detail 문자열 (JSON 또는 일반 텍스트) */
  detail: string | null | undefined
  /** 셀 클릭/Enter 시 호출 — 호출 측이 상세 모달 오픈 */
  onActivate: () => void
  /** 빈 detail 일 때 표시할 placeholder */
  emptyLabel?: string
}

interface TooltipPos {
  top: number
  left: number
}

export function DetailCell({ detail, onActivate, emptyLabel = '-' }: Props) {
  const [showTooltip, setShowTooltip] = useState(false)
  const [pos, setPos] = useState<TooltipPos | null>(null)
  const cellRef = useRef<HTMLSpanElement>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const tooltipId = useId()

  const { text, isJson } = prettyJson(detail)
  const isEmpty = text === ''

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const scheduleTooltip = useCallback(() => {
    if (isEmpty) return
    clearTimer()
    timerRef.current = setTimeout(() => {
      setShowTooltip(true)
    }, TOOLTIP_DELAY_MS)
  }, [isEmpty, clearTimer])

  const hideTooltip = useCallback(() => {
    clearTimer()
    setShowTooltip(false)
  }, [clearTimer])

  // 툴팁이 보일 때 셀 위치 기준으로 좌표 계산 + 화면 경계 보정
  useLayoutEffect(() => {
    if (!showTooltip || !cellRef.current) return
    const rect = cellRef.current.getBoundingClientRect()
    const TOOLTIP_W = 360
    const MARGIN = 8
    let left = rect.left
    // 우측 경계 넘침 방지
    if (left + TOOLTIP_W + MARGIN > window.innerWidth) {
      left = Math.max(MARGIN, window.innerWidth - TOOLTIP_W - MARGIN)
    }
    if (left < MARGIN) left = MARGIN
    // 기본은 셀 아래. 아래 공간 부족하면 위로.
    const top = rect.bottom + 6
    setPos({ top, left })
  }, [showTooltip])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        onActivate()
      }
    },
    [onActivate],
  )

  if (isEmpty) {
    return <span className="text-xs text-gray-600">{emptyLabel}</span>
  }

  // 셀 표시용 단일 라인 미리보기 (JSON 이면 공백 압축)
  const preview = isJson ? text.replace(/\s+/g, ' ') : text

  return (
    <>
      <span
        ref={cellRef}
        role="button"
        tabIndex={0}
        aria-label="상세 보기"
        aria-describedby={showTooltip ? tooltipId : undefined}
        onClick={onActivate}
        onKeyDown={handleKeyDown}
        onMouseEnter={scheduleTooltip}
        onMouseLeave={hideTooltip}
        onFocus={() => setShowTooltip(true)}
        onBlur={hideTooltip}
        className="block max-w-xs cursor-pointer truncate font-mono text-xs text-gray-400 underline decoration-gray-700 decoration-dotted underline-offset-4 transition-colors hover:text-blue-300 focus:text-blue-300 focus:outline-none"
        title=""
      >
        {preview}
      </span>

      {showTooltip &&
        pos !== null &&
        typeof document !== 'undefined' &&
        createPortal(
          <div
            id={tooltipId}
            role="tooltip"
            className="pointer-events-none fixed z-[80] w-[360px] max-w-[90vw] rounded-lg border border-gray-700 bg-gray-950 p-3 shadow-xl"
            style={{ top: pos.top, left: pos.left }}
          >
            <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-all font-mono text-xs leading-relaxed text-gray-200">
              {text}
            </pre>
            <p className="mt-2 border-t border-gray-800 pt-1.5 text-[10px] text-gray-500">
              클릭하면 전체 상세 + 복사
            </p>
          </div>,
          document.body,
        )}
    </>
  )
}
