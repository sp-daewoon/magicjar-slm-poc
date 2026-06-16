/**
 * JournalEntryFilterBar — 매매일지 필터 바
 *
 * ADR 0072 §8 + spec §3-3 Phase 3 정합:
 * - 기간 picker (from · to ISO date string)
 * - 종목명/코드 텍스트 검색
 */
import { useEffect, useState, type FormEvent } from 'react'
import type { JournalEventType } from '../api/journalApi'

export const EVENT_TYPE_LABELS: Record<JournalEventType, string> = {
  ORDER_FILLED: '체결',
}

export interface JournalEntryFilterState {
  from: string
  to: string
  instrumentCode: string
  instrumentInput: string
}

interface JournalEntryFilterBarProps {
  filter: JournalEntryFilterState
  onChange: (next: JournalEntryFilterState) => void
  onReset: () => void
}

export function JournalEntryFilterBar({ filter, onChange, onReset }: JournalEntryFilterBarProps) {
  const [draftInstrumentInput, setDraftInstrumentInput] = useState(filter.instrumentInput)
  const dateInputClass =
    'h-10 rounded-lg border border-gray-700 bg-gray-900 px-3 text-sm text-gray-200 placeholder:text-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'
  const searchInputClass =
    'h-10 rounded-lg border border-gray-700 bg-gray-900 px-3 text-sm text-white placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500'

  useEffect(() => {
    setDraftInstrumentInput(filter.instrumentInput)
  }, [filter.instrumentInput])

  function handleInstrumentSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const trimmed = draftInstrumentInput.trim()
    onChange({
      ...filter,
      instrumentCode: extractInstrumentCode(trimmed),
      instrumentInput: trimmed,
    })
  }

  function handleReset() {
    setDraftInstrumentInput('')
    onReset()
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border border-gray-800 bg-gray-900/60 p-3">
      {/* 기간 시작 */}
      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-gray-400">기간 시작</span>
        <input
          type="date"
          value={filter.from}
          onChange={(e) => onChange({ ...filter, from: e.target.value })}
          className={dateInputClass}
        />
      </label>

      {/* 기간 종료 */}
      <label className="flex flex-col gap-1">
        <span className="text-xs font-medium text-gray-400">기간 종료</span>
        <input
          type="date"
          value={filter.to}
          onChange={(e) => onChange({ ...filter, to: e.target.value })}
          className={dateInputClass}
        />
      </label>

      <form onSubmit={handleInstrumentSubmit} className="grid flex-1 gap-2 sm:grid-cols-[minmax(220px,1fr)_auto_auto]">
        <input
          type="search"
          value={draftInstrumentInput}
          onChange={(e) => setDraftInstrumentInput(e.target.value)}
          placeholder="종목명 또는 코드"
          aria-label="종목 검색"
          className={searchInputClass}
        />
        <button
          type="submit"
          className="h-10 rounded-lg bg-blue-600 px-4 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
        >
          검색
        </button>
        <button
          type="button"
          onClick={handleReset}
          className="h-10 rounded-lg border border-gray-700 px-3 text-sm text-gray-400 transition-colors hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
        >
          초기화
        </button>
      </form>
    </div>
  )
}

function extractInstrumentCode(value: string): string {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : ''
}
