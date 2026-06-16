/**
 * CandleCountSelector — 차트 봉수 선택 컴포넌트 (Phase 4-A · ADR 0093 §결정 LOCK)
 *
 * UI: select dropdown — 300 / 600 / 1200 / 3000 / 직접입력
 *   - 직접입력 시 input number (clamp: 1 ≤ value ≤ timeframe별 max)
 *   - "표시 봉수" 사용자 시각 라벨
 *   - aria-label 정합 (접근성)
 *
 * 동작:
 *   - select 변경 시 onChange(value) 즉시 호출
 *   - 직접입력 모드 진입 시 input 노출 + 사용자 enter/blur 시 clamp 후 onChange
 *   - max 초과 입력 시 max로 자동 정정 + 부드러운 시각 피드백
 *
 * Phase 4-B (localStorage)는 상위 SymbolChart가 책임 — 본 컴포넌트는 controlled.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CandleInterval } from '../../api/symbol'
import {
  CANDLE_COUNT_MAX_BY_INTERVAL,
  CANDLE_COUNT_PRESETS,
  MIN_CANDLE_COUNT,
  clampCandleCount,
} from './candleCountMaxByInterval'

interface CandleCountSelectorProps {
  /** 현재 선택된 봉수 (controlled) */
  value: number
  /** 현재 timeframe — max cap 결정 */
  interval: CandleInterval
  /** 변경 콜백 (clamp 적용된 값 전달) */
  onChange: (value: number) => void
}

/** select 내부 sentinel — preset이 아닌 "직접입력" 모드 진입 */
const CUSTOM_OPTION = 'custom'

export function CandleCountSelector({ value, interval, onChange }: CandleCountSelectorProps) {
  // value가 preset에 포함되면 select 그대로, 아니면 직접입력 모드.
  const isPreset = CANDLE_COUNT_PRESETS.includes(value)
  const [isCustom, setIsCustom] = useState<boolean>(!isPreset)
  // input에 표시할 임시 문자열 (사용자 타이핑 중에는 clamp 미적용)
  const [draft, setDraft] = useState<string>(String(value))
  const inputRef = useRef<HTMLInputElement | null>(null)

  const max = CANDLE_COUNT_MAX_BY_INTERVAL[interval]

  // interval 변경 시 현재 value가 새 max 초과면 clamp 후 부모에 통보
  useEffect(() => {
    const clamped = clampCandleCount(value, interval)
    if (clamped !== value) {
      onChange(clamped)
      setDraft(String(clamped))
    }
    // interval 변경 시 draft도 sync (사용자가 다른 interval로 갈 때 stale 방지)
    // — value도 동시에 변경되므로 위 clamp 분기에서 처리됨.
    // 이 effect는 interval 변경 시 max 검증 path만 책임.
  }, [interval, value, onChange])

  // 외부에서 value가 바뀌면 draft sync (controlled 정합)
  useEffect(() => {
    setDraft(String(value))
    if (CANDLE_COUNT_PRESETS.includes(value)) {
      setIsCustom(false)
    }
  }, [value])

  const selectValue = useMemo<string>(() => {
    if (isCustom || !CANDLE_COUNT_PRESETS.includes(value)) return CUSTOM_OPTION
    return String(value)
  }, [isCustom, value])

  const handleSelectChange = useCallback(
    (e: React.ChangeEvent<HTMLSelectElement>) => {
      const next = e.target.value
      if (next === CUSTOM_OPTION) {
        setIsCustom(true)
        // 직접입력 진입 시 input에 포커스 (한 tick 뒤)
        setTimeout(() => inputRef.current?.focus(), 0)
        return
      }
      setIsCustom(false)
      const parsed = Number.parseInt(next, 10)
      if (Number.isFinite(parsed)) {
        const clamped = clampCandleCount(parsed, interval)
        setDraft(String(clamped))
        onChange(clamped)
      }
    },
    [interval, onChange],
  )

  const commitDraft = useCallback(() => {
    const parsed = Number.parseInt(draft, 10)
    const clamped = clampCandleCount(parsed, interval)
    setDraft(String(clamped))
    onChange(clamped)
  }, [draft, interval, onChange])

  const handleInputKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === 'Enter') {
        e.preventDefault()
        commitDraft()
        inputRef.current?.blur()
      }
    },
    [commitDraft],
  )

  return (
    <div
      className="flex items-center gap-1.5"
      role="group"
      aria-label="차트 표시 봉수 선택"
    >
      <label
        htmlFor="candle-count-select"
        className="text-xs text-gray-400 whitespace-nowrap"
      >
        표시 봉수
      </label>
      <select
        id="candle-count-select"
        value={selectValue}
        onChange={handleSelectChange}
        aria-label="차트 표시 봉수 preset 선택"
        className="rounded-md bg-gray-800 border border-gray-700 text-gray-200 text-xs px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-400 hover:bg-gray-700 transition-colors"
      >
        {CANDLE_COUNT_PRESETS.map((preset) => (
          <option key={preset} value={preset}>
            {preset.toLocaleString('ko-KR')}
          </option>
        ))}
        <option value={CUSTOM_OPTION}>직접입력</option>
      </select>

      {(isCustom || !CANDLE_COUNT_PRESETS.includes(value)) && (
        <>
          <input
            ref={inputRef}
            type="number"
            min={MIN_CANDLE_COUNT}
            max={max}
            step={1}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={commitDraft}
            onKeyDown={handleInputKeyDown}
            aria-label={`차트 표시 봉수 직접입력 (최소 ${MIN_CANDLE_COUNT}, 최대 ${max.toLocaleString('ko-KR')})`}
            className="w-20 rounded-md bg-gray-800 border border-gray-700 text-gray-200 text-xs px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-400 hover:bg-gray-700 transition-colors"
          />
          <span
            className="text-[10px] text-gray-500 whitespace-nowrap"
            aria-hidden="true"
          >
            최대 {max.toLocaleString('ko-KR')}
          </span>
        </>
      )}
    </div>
  )
}
