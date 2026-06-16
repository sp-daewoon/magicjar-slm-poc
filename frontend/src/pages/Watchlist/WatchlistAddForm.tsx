/**
 * 관심종목 등록 폼 컴포넌트 (PR-11b 정정 — T-IM1)
 *
 * PR-7 옵션 C (6자리 raw code 직접 입력) → InstrumentSearchInput 자동완성으로 전환.
 * graceful: InstrumentSearchInput 5xx 3회 → raw input fallback toggle (컴포넌트 내부 처리).
 *
 * dispatch-spec §4-2-G 정합 (WatchlistAddForm 정정 = 본 PR-11b 영역).
 * 룰 5: LLM provider import 0건.
 */
import axios from 'axios'
import { useRef, useState } from 'react'
import { InstrumentSearchInput } from '../../components/InstrumentSearchInput'
import { useAddWatchlist } from '../../api/hooks/useWatchlist'
import type { WatchlistErrorResponse } from '../../api/watchlist'
import type { InstrumentSearchResultDto } from '../../api/instrument'

interface WatchlistAddFormProps {
  /** 외부에서 input에 포커스를 줄 수 있도록 ref 노출 */
  inputRef?: React.RefObject<HTMLInputElement | null>
}

export function WatchlistAddForm({ inputRef: _inputRef }: WatchlistAddFormProps) {
  // 선택된 종목 (code + name)
  const [selectedCode, setSelectedCode] = useState('')
  const [selectedName, setSelectedName] = useState('')
  const [searchValue, setSearchValue] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const successTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const { mutate: addWatchlist, isPending } = useAddWatchlist()

  function handleSelect(result: InstrumentSearchResultDto) {
    setSelectedCode(result.code)
    setSelectedName(result.name !== result.code ? result.name : '')
    setSearchValue(`${result.name !== result.code ? result.name + ' ' : ''}(${result.code})`)
    setValidationError(null)
    setSuccessMessage(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setValidationError(null)
    setSuccessMessage(null)

    if (!selectedCode || !/^\d{6}$/.test(selectedCode)) {
      setValidationError('종목을 선택해주세요 (자동완성 목록에서 선택)')
      return
    }

    addWatchlist(
      { code: selectedCode },
      {
        onSuccess: () => {
          const displayName = selectedName || selectedCode
          setSelectedCode('')
          setSelectedName('')
          setSearchValue('')
          setValidationError(null)
          setSuccessMessage(`${displayName} 등록되었습니다`)
          // 1초 후 성공 메시지 제거
          if (successTimerRef.current) clearTimeout(successTimerRef.current)
          successTimerRef.current = setTimeout(() => setSuccessMessage(null), 1000)
        },
        onError: (err) => {
          if (axios.isAxiosError(err)) {
            const data = err.response?.data as Partial<WatchlistErrorResponse> | undefined
            if (err.response?.status === 409) {
              setValidationError(data?.message ?? '이미 등록된 종목입니다')
            } else if (err.response?.status === 400) {
              setValidationError(data?.message ?? '유효하지 않은 종목 코드입니다')
            } else {
              setValidationError('등록 중 오류가 발생했습니다. 다시 시도해 주세요.')
            }
          } else {
            setValidationError('등록 중 오류가 발생했습니다. 다시 시도해 주세요.')
          }
        },
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2" noValidate>
      <div className="flex gap-2">
        <div className="flex-1">
          <label htmlFor="watchlist-instrument-search" className="sr-only">
            종목 검색 (자동완성)
          </label>
          {/* InstrumentSearchInput — 자동완성 dropdown (PR-11b 전환) */}
          <InstrumentSearchInput
            value={searchValue}
            onSelect={(result) => {
              handleSelect(result)
            }}
            disabled={isPending}
            placeholder="종목명 또는 코드 입력 (예: 삼성전자)"
          />
        </div>
        <button
          type="submit"
          disabled={isPending || !selectedCode}
          className="rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-2 focus:ring-offset-gray-950 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {isPending ? '등록 중...' : '등록'}
        </button>
      </div>

      {/* 선택된 종목 표시 */}
      {selectedCode && (
        <p className="text-xs text-blue-400">
          선택: {selectedName && <span className="font-medium">{selectedName}</span>}{' '}
          <span className="font-mono">{selectedCode}</span>
        </p>
      )}

      {/* 에러 메시지 */}
      {validationError != null && (
        <p id="watchlist-add-error" className="text-xs text-red-400" role="alert">
          {validationError}
        </p>
      )}

      {/* 성공 메시지 (1초 후 자동 제거) */}
      {successMessage != null && (
        <p id="watchlist-add-success" className="text-xs text-green-400" role="status">
          {successMessage}
        </p>
      )}
    </form>
  )
}
