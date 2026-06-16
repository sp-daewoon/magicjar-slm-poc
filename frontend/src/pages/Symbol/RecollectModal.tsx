/**
 * RecollectModal — 종목 상세 차트 재수집 모달 (ADR 0120 §6 — 기준봉/파생봉 통일 모델)
 *
 * 기존 window.confirm(interval 단위 단순 트리거)을 대체하는 체크박스 2그룹 모달.
 *
 * ## 구성 (사용자 결재 spec)
 *   ┌─ 분 단위 (기준봉 1m) ────────────────────────┐
 *   │ ☑ 1m  [기간: 최근 5일/10일/30일/90일 ▾]       │  KIS raw fetch
 *   │   └ 파생 합산 (기본 전체 체크):                │
 *   │     ☑ 3m ☑ 5m ☑ 15m ☑ 30m ☑ 90m ☑ 1h ☑ 4h ☑ 12h │
 *   ├─ 일 단위 (기준봉 1d) ────────────────────────┤
 *   │ ☐ 1d  [강제 재수집 · 전 기간]                 │  KIS raw fetch
 *   │   └ 파생 합산 (기본 전체 체크):                │
 *   │     ☐ 1wk ☐ 1mo ☐ 1y                          │
 *   └──────────────────────────────────────────────┘
 *
 * ## 동작
 *   - 기본 상태: 1m 체크 + 1m 파생 전체 체크 / 1d 그룹 미체크.
 *   - 기준봉(1m/1d) 미체크면 그 파생 체크박스 비활성(합산은 기준 재수집과 함께만 의미).
 *   - 1m 파생만 다시 만들기(기준 재수집 없이 합산만)는 v1 범위 외 — 기준봉 체크 강제.
 *   - [재수집 시작] → 선택된 interval CSV + 1m 기간(minuteRecentDays) 을 백엔드 job 으로 전송.
 *
 * ## 백엔드 매핑 (ADR 0120)
 *   - 1m 체크 → intervals 에 1m + 선택 파생(3m~12h). minuteRecentDays = 기간 콤보값.
 *   - 1d 체크 → intervals 에 1d + 선택 파생(1wk/1mo/1y). 1d 는 전기간 강제(롤업 베이스).
 *   - 진행 표시는 상위 SymbolChart 의 useCandleRecollectJob STOMP+poll 흐름 재사용.
 */
import { useCallback, useMemo, useState } from 'react'
import type { CandleInterval } from '../../api/symbol'

/** 1m 파생 합산 interval (3m~12h) — ADR 0062 합산봉. */
const MINUTE_DERIVED: ReadonlyArray<CandleInterval> = ['3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h']
/** 1d 파생 롤업 interval (1wk/1mo/1y) — ADR 0120 1d 롤업. */
const DAILY_DERIVED: ReadonlyArray<CandleInterval> = ['1wk', '1mo', '1y']

/** 1m 기간 콤보 — "최근 N 거래일" (KIS 1m 보관 한도 내). */
const MINUTE_RANGE_PRESETS: ReadonlyArray<{ days: number; label: string }> = [
  { days: 5, label: '최근 5일' },
  { days: 10, label: '최근 10일' },
  { days: 30, label: '최근 30일' },
  { days: 90, label: '최근 90일' },
]

export interface RecollectModalSubmit {
  /** 선택된 재수집 interval CSV (1m·파생·1d·파생). */
  intervals: string[]
  /** 1m 기준봉 기간(최근 N 거래일). 1m 미체크면 undefined. */
  minuteRecentDays?: number
}

interface RecollectModalProps {
  code: string
  symbolName?: string | null
  isSubmitting: boolean
  onClose: () => void
  onSubmit: (input: RecollectModalSubmit) => void
}

function intervalLabel(interval: CandleInterval): string {
  return interval
}

export function RecollectModal({ code, symbolName, isSubmitting, onClose, onSubmit }: RecollectModalProps) {
  // 기준봉 체크 상태 (기본: 1m 체크 / 1d 미체크)
  const [minuteBaseChecked, setMinuteBaseChecked] = useState(true)
  const [dailyBaseChecked, setDailyBaseChecked] = useState(false)

  // 1m 기간 (기본: 최근 30일)
  const [minuteRecentDays, setMinuteRecentDays] = useState<number>(30)

  // 파생 체크 상태 (기본: 1m 파생 전체 / 1d 파생 전체 — 그룹 미체크 시 비활성)
  const [minuteDerived, setMinuteDerived] = useState<Set<CandleInterval>>(() => new Set(MINUTE_DERIVED))
  const [dailyDerived, setDailyDerived] = useState<Set<CandleInterval>>(() => new Set(DAILY_DERIVED))

  const toggleDerived = useCallback(
    (
      interval: CandleInterval,
      setter: React.Dispatch<React.SetStateAction<Set<CandleInterval>>>,
    ) => {
      setter((prev) => {
        const next = new Set(prev)
        if (next.has(interval)) next.delete(interval)
        else next.add(interval)
        return next
      })
    },
    [],
  )

  const selectedIntervals = useMemo<string[]>(() => {
    const result: string[] = []
    if (minuteBaseChecked) {
      result.push('1m')
      for (const d of MINUTE_DERIVED) {
        if (minuteDerived.has(d)) result.push(d)
      }
    }
    if (dailyBaseChecked) {
      result.push('1d')
      for (const d of DAILY_DERIVED) {
        if (dailyDerived.has(d)) result.push(d)
      }
    }
    return result
  }, [minuteBaseChecked, dailyBaseChecked, minuteDerived, dailyDerived])

  const canSubmit = selectedIntervals.length > 0 && !isSubmitting

  const handleSubmit = useCallback(() => {
    if (!canSubmit) return
    onSubmit({
      intervals: selectedIntervals,
      minuteRecentDays: minuteBaseChecked ? minuteRecentDays : undefined,
    })
  }, [canSubmit, onSubmit, selectedIntervals, minuteBaseChecked, minuteRecentDays])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="캔들 재수집"
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !isSubmitting) onClose()
      }}
    >
      <div className="w-full max-w-md rounded-xl border border-gray-700 bg-gray-900 shadow-2xl">
        {/* 헤더 */}
        <div className="flex items-center justify-between border-b border-gray-800 px-4 py-3">
          <h2 className="text-sm font-semibold text-gray-100">
            재수집 — {symbolName ? `${symbolName} (${code})` : code}
          </h2>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="rounded p-1 text-gray-500 transition-colors hover:bg-gray-800 hover:text-gray-200 disabled:opacity-50"
            aria-label="재수집 모달 닫기"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* 본문 */}
        <div className="max-h-[60vh] space-y-4 overflow-y-auto px-4 py-4">
          {/* ── 분 단위 그룹 (기준봉 1m) ── */}
          <section className="rounded-lg border border-gray-800 bg-gray-950/40 p-3">
            <label className="flex items-center gap-2 text-sm font-medium text-gray-200">
              <input
                type="checkbox"
                checked={minuteBaseChecked}
                onChange={(e) => setMinuteBaseChecked(e.target.checked)}
                disabled={isSubmitting}
                className="h-4 w-4 rounded border-gray-600 bg-gray-800 text-blue-500 focus:ring-blue-400"
                aria-label="1분봉 기준봉 재수집"
              />
              <span>1m</span>
              <span className="text-[11px] font-normal text-gray-500">기준봉 · KIS 재수집</span>
            </label>

            <div className="mt-2 flex items-center gap-2 pl-6">
              <span className="text-xs text-gray-400">기간</span>
              <select
                value={minuteRecentDays}
                onChange={(e) => setMinuteRecentDays(Number(e.target.value))}
                disabled={!minuteBaseChecked || isSubmitting}
                aria-label="1분봉 재수집 기간 (최근 거래일)"
                className="rounded-md border border-gray-700 bg-gray-800 px-2 py-1 text-xs text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:opacity-40"
              >
                {MINUTE_RANGE_PRESETS.map((p) => (
                  <option key={p.days} value={p.days}>
                    {p.label}
                  </option>
                ))}
              </select>
            </div>

            <div className="mt-2 pl-6">
              <p className="mb-1.5 text-[11px] text-gray-500">합산 재생성</p>
              <div className="flex flex-wrap gap-1.5">
                {MINUTE_DERIVED.map((d) => (
                  <label
                    key={d}
                    className={`flex items-center gap-1 rounded px-1.5 py-0.5 text-xs ${
                      minuteBaseChecked ? 'text-gray-300' : 'text-gray-600'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={minuteDerived.has(d)}
                      onChange={() => toggleDerived(d, setMinuteDerived)}
                      disabled={!minuteBaseChecked || isSubmitting}
                      className="h-3.5 w-3.5 rounded border-gray-600 bg-gray-800 text-teal-500 focus:ring-teal-400"
                      aria-label={`${intervalLabel(d)} 합산 재생성`}
                    />
                    {intervalLabel(d)}
                  </label>
                ))}
              </div>
            </div>
          </section>

          {/* ── 일 단위 그룹 (기준봉 1d) ── */}
          <section className="rounded-lg border border-gray-800 bg-gray-950/40 p-3">
            <label className="flex items-center gap-2 text-sm font-medium text-gray-200">
              <input
                type="checkbox"
                checked={dailyBaseChecked}
                onChange={(e) => setDailyBaseChecked(e.target.checked)}
                disabled={isSubmitting}
                className="h-4 w-4 rounded border-gray-600 bg-gray-800 text-blue-500 focus:ring-blue-400"
                aria-label="일봉 기준봉 재수집"
              />
              <span>1d</span>
              <span className="text-[11px] font-normal text-gray-500">기준봉 · KIS 재수집 (전 기간)</span>
            </label>

            <div className="mt-2 pl-6">
              <p className="mb-1.5 text-[11px] text-gray-500">합산 재생성 (1d 롤업)</p>
              <div className="flex flex-wrap gap-1.5">
                {DAILY_DERIVED.map((d) => (
                  <label
                    key={d}
                    className={`flex items-center gap-1 rounded px-1.5 py-0.5 text-xs ${
                      dailyBaseChecked ? 'text-gray-300' : 'text-gray-600'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={dailyDerived.has(d)}
                      onChange={() => toggleDerived(d, setDailyDerived)}
                      disabled={!dailyBaseChecked || isSubmitting}
                      className="h-3.5 w-3.5 rounded border-gray-600 bg-gray-800 text-teal-500 focus:ring-teal-400"
                      aria-label={`${intervalLabel(d)} 롤업 재생성`}
                    />
                    {intervalLabel(d)}
                  </label>
                ))}
              </div>
            </div>
          </section>

          {selectedIntervals.length === 0 && (
            <p className="text-xs text-amber-400" role="status">
              재수집할 기준봉(1m 또는 1d)을 1개 이상 선택해 주세요.
            </p>
          )}
        </div>

        {/* 푸터 */}
        <div className="flex items-center justify-end gap-2 border-t border-gray-800 px-4 py-3">
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            className="rounded-md bg-gray-800 px-3 py-1.5 text-xs text-gray-300 transition-colors hover:bg-gray-700 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={handleSubmit}
            disabled={!canSubmit}
            className="flex items-center gap-1.5 rounded-md bg-amber-700 px-3 py-1.5 text-xs font-medium text-amber-50 transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSubmitting ? (
              <>
                <span className="h-3 w-3 animate-spin rounded-full border-2 border-amber-900 border-t-amber-100" aria-hidden="true" />
                재수집 중...
              </>
            ) : (
              '재수집 시작'
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
