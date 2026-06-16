/**
 * 종목 자동완성 검색 입력 컴포넌트 (T-IM1)
 *
 * 기능:
 * - debounce 200ms 후 API 호출
 * - 한글 IME composition 처리 (design §11-6):
 *   compositionstart → isComposing=true (API 호출 차단)
 *   compositionend   → isComposing=false + 즉시 검색
 * - keyboard navigation: Up/Down/Enter
 * - 클릭 또는 Enter → onSelect callback
 * - 검색 5xx → "검색 일시 불가능" 표시 + raw input fallback toggle
 * - graceful: 서버 오류 3회 누적 시 raw input mode 전환
 */
import { useEffect, useRef, useState, useCallback } from 'react'
import type { InstrumentSearchResultDto } from '../api/instrument'
import { useInstrumentSearch } from '../api/hooks/useInstrumentSearch'

interface InstrumentSearchInputProps {
  /** 현재 선택된 종목코드 */
  value: string
  /** 종목 선택 콜백 */
  onSelect: (result: InstrumentSearchResultDto) => void
  /** 직접 입력 중인 원문 콜백 */
  onRawChange?: (value: string) => void
  /** 비활성화 여부 */
  disabled?: boolean
  /** placeholder 텍스트 */
  placeholder?: string
}

export function InstrumentSearchInput({
  value,
  onSelect,
  onRawChange,
  disabled = false,
  placeholder = '종목명 또는 코드 검색',
}: InstrumentSearchInputProps) {
  const [inputValue, setInputValue] = useState(value)
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [isComposing, setIsComposing] = useState(false)
  const [isOpen, setIsOpen] = useState(false)
  const [isFocused, setIsFocused] = useState(false)
  const [hasUserEdited, setHasUserEdited] = useState(false)
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  const [serverErrorCount, setServerErrorCount] = useState(0)
  const [rawInputMode, setRawInputMode] = useState(false)

  const inputRef = useRef<HTMLInputElement>(null)
  const dropdownRef = useRef<HTMLDivElement>(null)
  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const suppressNextDebounceRef = useRef(false)
  const inputValueRef = useRef(inputValue)

  useEffect(() => {
    inputValueRef.current = inputValue
  }, [inputValue])

  // debounce 200ms (IME 조합 중에는 스킵)
  useEffect(() => {
    if (isComposing) return
    if (suppressNextDebounceRef.current) {
      suppressNextDebounceRef.current = false
      return
    }
    if (!hasUserEdited && isInstrumentDisplayLabel(inputValue)) {
      setDebouncedQuery('')
      return
    }
    if (debounceTimer.current) clearTimeout(debounceTimer.current)
    debounceTimer.current = setTimeout(() => {
      setDebouncedQuery(inputValue.trim())
    }, 200)
    return () => {
      if (debounceTimer.current) clearTimeout(debounceTimer.current)
    }
  }, [inputValue, isComposing, hasUserEdited])

  const { results, isLoading, isServerError } = useInstrumentSearch({
    query: debouncedQuery,
    limit: 10,
    isComposing,
  })

  // 5xx 오류 누적 → raw input mode 전환
  useEffect(() => {
    if (isServerError) {
      setServerErrorCount((prev) => {
        const next = prev + 1
        if (next >= 3) {
          setRawInputMode(true)
        }
        return next
      })
    }
  }, [isServerError])

  // 사용자가 입력 필드에 머무르는 동안에만 dropdown을 연다.
  useEffect(() => {
    if (!isFocused) {
      setIsOpen(false)
      return
    }
    if (results.length > 0 && debouncedQuery.length >= 1) {
      setIsOpen(true)
      setHighlightedIndex(-1)
    } else if (!isLoading && debouncedQuery.length >= 1) {
      setIsOpen(false)
    }
  }, [results, debouncedQuery, isLoading, isFocused])

  // 외부 클릭 시 dropdown 닫기
  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        inputRef.current &&
        !inputRef.current.contains(e.target as Node)
      ) {
        setIsFocused(false)
        setIsOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // 부모 value 변경 시 inputValue 동기화
  useEffect(() => {
    const nextValue = value.trim()
    const currentInputValue = inputValueRef.current
    const currentCode = extractInstrumentCode(currentInputValue)
    if (/^\d{6}$/.test(nextValue) && currentCode === nextValue && isInstrumentDisplayLabel(currentInputValue)) {
      setHasUserEdited(false)
      setIsOpen(false)
      return
    }
    setInputValue(nextValue)
    setHasUserEdited(false)
    setIsOpen(false)
    setHighlightedIndex(-1)
    if (!nextValue || isInstrumentDisplayLabel(nextValue)) {
      suppressNextDebounceRef.current = true
      setDebouncedQuery('')
    }
  }, [value])

  useEffect(() => {
    if (hasUserEdited || isFocused) return
    const code = extractInstrumentCode(value)
    if (!code || isInstrumentDisplayLabel(inputValue)) return
    const match = results.find((result) => result.code === code)
    if (!match) return
    suppressNextDebounceRef.current = true
    setInputValue(formatInstrumentDisplay(match))
    setDebouncedQuery('')
    setIsOpen(false)
  }, [results, value, inputValue, hasUserEdited, isFocused])

  const handleSelect = useCallback(
    (result: InstrumentSearchResultDto) => {
      suppressNextDebounceRef.current = true
      setInputValue(formatInstrumentDisplay(result))
      setDebouncedQuery('')
      setHasUserEdited(false)
      setIsOpen(false)
      setHighlightedIndex(-1)
      onSelect(result)
    },
    [onSelect],
  )

  function handleInputChange(e: React.ChangeEvent<HTMLInputElement>) {
    const next = e.target.value
    setHasUserEdited(true)
    setInputValue(next)
    onRawChange?.(next)
    setServerErrorCount(0) // 새 입력 시 오류 카운터 리셋
  }

  // ── IME composition 핸들러 (design §11-6) ──────────────────────────────
  function handleCompositionStart() {
    setIsComposing(true)
  }

  function handleCompositionEnd(e: React.CompositionEvent<HTMLInputElement>) {
    setIsComposing(false)
    setHasUserEdited(true)
    // IME 조합 완료 후 즉시 검색 (debounce 없이)
    const finalValue = e.currentTarget.value
    setDebouncedQuery(finalValue.trim())
  }

  // ── 키보드 네비게이션 ────────────────────────────────────────────────
  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (!isOpen || results.length === 0) return

    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault()
        setHighlightedIndex((prev) => Math.min(prev + 1, results.length - 1))
        break
      case 'ArrowUp':
        e.preventDefault()
        setHighlightedIndex((prev) => Math.max(prev - 1, -1))
        break
      case 'Enter':
        e.preventDefault()
        if (highlightedIndex >= 0 && highlightedIndex < results.length) {
          handleSelect(results[highlightedIndex])
        } else if (results.length === 1) {
          handleSelect(results[0])
        }
        break
      case 'Escape':
        setIsOpen(false)
        setHighlightedIndex(-1)
        break
    }
  }

  // raw input mode: 자동완성 없이 직접 입력
  if (rawInputMode) {
    return (
      <div className="relative">
        <input
          ref={inputRef}
          type="text"
          value={inputValue}
          onChange={(e) => {
            setInputValue(e.target.value)
            onRawChange?.(e.target.value)
          }}
          onBlur={() => {
            // raw mode에서 6자리 숫자면 fake result로 onSelect 호출
            const trimmed = inputValue.trim()
            if (/^\d{6}$/.test(trimmed)) {
              onSelect({ code: trimmed, name: trimmed, market: 'UNKNOWN', sector: null, score: 1.0 })
            }
          }}
          placeholder="종목 코드 6자리 직접 입력 (검색 일시 불가)"
          disabled={disabled}
          className="w-full rounded-xl border border-yellow-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-yellow-500 transition-colors focus:border-yellow-400 focus:outline-none focus:ring-1 focus:ring-yellow-400 disabled:cursor-not-allowed disabled:opacity-50"
        />
        <p className="mt-1 text-xs text-yellow-400">검색 일시 불가능 — 코드 직접 입력</p>
        <button
          type="button"
          onClick={() => {
            setRawInputMode(false)
            setServerErrorCount(0)
          }}
          className="mt-1 text-xs text-blue-400 underline"
        >
          자동완성으로 전환
        </button>
      </div>
    )
  }

  return (
    <div className="relative">
      <input
        ref={inputRef}
        type="text"
        value={inputValue}
        onChange={handleInputChange}
        onCompositionStart={handleCompositionStart}
        onCompositionEnd={handleCompositionEnd}
        onKeyDown={handleKeyDown}
        onFocus={() => {
          setIsFocused(true)
          if (results.length > 0 && debouncedQuery.length >= 1) {
            setIsOpen(true)
          }
        }}
        placeholder={placeholder}
        disabled={disabled}
        autoComplete="off"
        className="w-full rounded-xl border border-gray-700 bg-gray-800 px-4 py-2.5 text-sm text-white placeholder-gray-500 transition-colors focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:cursor-not-allowed disabled:opacity-50"
        aria-label="종목 검색"
        aria-expanded={isOpen}
        aria-autocomplete="list"
        role="combobox"
        aria-owns="instrument-search-listbox"
      />

      {/* 로딩 인디케이터 */}
      {isLoading && (
        <div className="absolute right-3 top-1/2 -translate-y-1/2">
          <span className="text-xs text-gray-400">검색 중...</span>
        </div>
      )}

      {/* 서버 오류 (5xx) 메시지 */}
      {isServerError && serverErrorCount < 3 && (
        <p className="mt-1 text-xs text-yellow-400">검색 일시 불가능 — 직접 입력하거나 잠시 후 다시 시도해주세요</p>
      )}

      {/* 검색 결과 dropdown */}
      {isOpen && results.length > 0 && (
        <div
          ref={dropdownRef}
          id="instrument-search-listbox"
          role="listbox"
          aria-label="종목 검색 결과"
          className="absolute z-50 mt-1 w-full rounded-xl border border-gray-700 bg-gray-900 shadow-xl"
        >
          {results.map((result, idx) => (
            <button
              key={result.code}
              type="button"
              role="option"
              aria-selected={idx === highlightedIndex}
              onMouseEnter={() => setHighlightedIndex(idx)}
              onMouseLeave={() => setHighlightedIndex(-1)}
              onClick={() => handleSelect(result)}
              className={`flex w-full items-center justify-between px-4 py-2.5 text-left text-sm transition-colors first:rounded-t-xl last:rounded-b-xl ${
                idx === highlightedIndex ? 'bg-blue-600 text-white' : 'text-gray-200 hover:bg-gray-800'
              }`}
            >
              <span className="flex flex-col">
                <span className="font-medium">{result.name}</span>
                {result.sector && <span className="text-xs text-gray-400">{result.sector}</span>}
              </span>
              <span className="ml-2 flex flex-col items-end">
                <span className="font-mono text-xs text-gray-400">{result.code}</span>
                <span className="text-xs text-gray-500">{result.market}</span>
              </span>
            </button>
          ))}
        </div>
      )}

      {/* 검색어 있는데 결과 없음 */}
      {isOpen && !isLoading && debouncedQuery.length >= 1 && results.length === 0 && (
        <div className="absolute z-50 mt-1 w-full rounded-xl border border-gray-700 bg-gray-900 px-4 py-3 text-sm text-gray-400 shadow-xl">
          검색 결과가 없습니다
        </div>
      )}
    </div>
  )
}

function formatInstrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function isInstrumentDisplayLabel(value: string): boolean {
  return /\(\d{6}\)\s*$/.test(value.trim())
}

function extractInstrumentCode(value: string): string | null {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : null
}
