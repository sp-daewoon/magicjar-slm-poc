/**
 * StrategyFilterBar — 전략 목록 필터 바 (T-SIG Phase 3 — ADR 0066)
 *
 * - status 필터 탭 (전체·ACTIVE·BUY_BLOCKED·PAUSED·DISABLED)
 * - 키보드 네비게이션 (ArrowLeft/ArrowRight)
 */
import { useRef } from 'react'
import { type StrategyStatus } from '../../api/strategy'

export type StrategyFilterOption = StrategyStatus | 'ALL'

interface StrategyFilterBarProps {
  value: StrategyFilterOption
  onChange: (v: StrategyFilterOption) => void
}

const OPTIONS: { value: StrategyFilterOption; label: string }[] = [
  { value: 'ALL',      label: '전체' },
  { value: 'ACTIVE',   label: '자동매매중' },
  { value: 'BUY_BLOCKED', label: '매수차단' },
  { value: 'PAUSED',   label: '중지' },
  { value: 'DISABLED', label: '보호중지' },
]

const TAB_ACTIVE =
  'bg-blue-600 text-white'
const TAB_DEFAULT =
  'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200'

export function StrategyFilterBar({ value, onChange }: StrategyFilterBarProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  function handleKeyDown(e: React.KeyboardEvent, currentIdx: number) {
    let nextIdx = currentIdx
    if (e.key === 'ArrowRight') {
      nextIdx = (currentIdx + 1) % OPTIONS.length
      e.preventDefault()
    } else if (e.key === 'ArrowLeft') {
      nextIdx = (currentIdx - 1 + OPTIONS.length) % OPTIONS.length
      e.preventDefault()
    } else {
      return
    }
    const buttons = containerRef.current?.querySelectorAll('button')
    buttons?.[nextIdx]?.focus()
    onChange(OPTIONS[nextIdx].value)
  }

  return (
    <div
      ref={containerRef}
      role="tablist"
      aria-label="전략 상태 필터"
      className="flex items-center gap-1 rounded-lg bg-gray-900 p-1"
    >
      {OPTIONS.map((opt, idx) => (
        <button
          key={opt.value}
          role="tab"
          aria-selected={value === opt.value}
          tabIndex={value === opt.value ? 0 : -1}
          onClick={() => onChange(opt.value)}
          onKeyDown={(e) => handleKeyDown(e, idx)}
          className={[
            'rounded-md px-3 py-1.5 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-900',
            value === opt.value ? TAB_ACTIVE : TAB_DEFAULT,
          ].join(' ')}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}
