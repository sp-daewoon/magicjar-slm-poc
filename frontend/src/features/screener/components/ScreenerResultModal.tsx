/**
 * ScreenerResultModal — "이 전략으로 종목 찾기" 결과 모달
 *
 * ADR 0071 §8 H-1 채택 — Strategy Builder 합류.
 * SettingsModal 닫기 패턴 정합 (Esc·X·overlay click).
 *
 * 흐름:
 *   마운트/기준봉 변경 → useScreenerEvaluate mutation 호출 (inline 평가)
 *   loading: Spinner 표시
 *   error: 에러 메시지 + 재시도 버튼
 *   data: ScreenerSummary + ScreenerSkipReasonsBadge + ScreenerMatchTable
 *   footer: 닫기 + WatchlistBulkRegisterButton
 *
 * userId 누락 시 setup 안내 graceful (feedback_no_mock_data 정합).
 */
import { useEffect, useState } from 'react'
import { useModalBehavior } from '../../../hooks/useModalBehavior'
import { useScreenerEvaluate } from '../hooks/useScreenerEvaluate'
import { useBulkAddWatchlist } from '../hooks/useBulkAddWatchlist'
import { ScreenerMatchTable } from './ScreenerMatchTable'
import { ScreenerSkipReasonsBadge } from './ScreenerSkipReasonsBadge'
import { WatchlistBulkRegisterButton } from './WatchlistBulkRegisterButton'
import type { CandleInterval } from '../../../api/symbol'

interface Props {
  strategyId: number
  userId: number | null
  onClose: () => void
}

function XIcon() {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  )
}

function Spinner() {
  return (
    <div
      className="flex flex-col items-center justify-center gap-3 py-16"
      role="status"
      aria-label="universe 전체 평가 중"
    >
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-700 border-t-emerald-500" />
      <p className="text-sm text-gray-500">
        universe 전체 평가 중 (최대 ~30초)...
      </p>
    </div>
  )
}

const BASIS_INTERVAL_OPTIONS: { value: CandleInterval; label: string }[] = [
  { value: '1d', label: '일봉' },
  { value: '1m', label: '1분' },
  { value: '3m', label: '3분' },
  { value: '5m', label: '5분' },
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '90m', label: '90분' },
  { value: '1h', label: '1시간' },
  { value: '4h', label: '4시간' },
  { value: '12h', label: '장전체' },
  { value: '1wk', label: '주봉' },
  { value: '1mo', label: '월봉' },
  { value: '1y', label: '년봉' },
]

function hasCandleDataSkips(skippedReasons: Record<string, number>): boolean {
  return Object.keys(skippedReasons).some((reason) => reason.includes('캔들') || reason.includes('Candle'))
}

/** 통계 요약 row */
function ScreenerSummary({
  universeSize,
  evaluatedCount,
  matchedCount,
  elapsedMs,
  cacheHit,
}: {
  universeSize: number
  evaluatedCount: number
  matchedCount: number
  elapsedMs: number
  cacheHit: boolean
}) {
  return (
    <div className="flex flex-wrap gap-x-6 gap-y-1 rounded-lg bg-gray-800/60 px-4 py-3 text-sm">
      <span className="text-gray-400">
        Universe{' '}
        <span className="font-semibold text-white">{universeSize.toLocaleString()}</span>
      </span>
      <span className="text-gray-400">
        평가{' '}
        <span className="font-semibold text-white">{evaluatedCount.toLocaleString()}</span>
      </span>
      <span className="text-gray-400">
        매칭{' '}
        <span className="font-semibold text-emerald-400">{matchedCount.toLocaleString()}</span>
      </span>
      <span className="text-gray-400">
        소요{' '}
        <span className="font-semibold text-white">
          {elapsedMs < 1000
            ? `${elapsedMs}ms`
            : `${(elapsedMs / 1000).toFixed(1)}s`}
        </span>
      </span>
      {cacheHit && (
        <span className="rounded-full bg-blue-900/40 px-2 py-0.5 text-xs font-medium text-blue-300">
          캐시 HIT
        </span>
      )}
    </div>
  )
}

export function ScreenerResultModal({ strategyId, userId, onClose }: Props) {
  const [selectedCodes, setSelectedCodes] = useState<Set<string>>(new Set())
  const [basisInterval, setBasisInterval] = useState<CandleInterval>('1d')

  const { mutate, data, isPending, error, reset } = useScreenerEvaluate()
  const bulkAdd = useBulkAddWatchlist()

  // 마운트 시 즉시 평가 호출 (ADR 0071 §2-1 동기 inline — 마운트 폴링 0)
  useEffect(() => {
    if (userId === null) return
    setSelectedCodes(new Set())
    mutate({ userId, strategyId, interval: basisInterval, limit: 50 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [strategyId, userId, basisInterval])

  // Esc 닫기 + body scroll lock (공용 훅 — useModalBehavior)
  useModalBehavior({ open: true, onClose })

  function handleOverlayClick(e: React.MouseEvent<HTMLDivElement>) {
    if (e.target === e.currentTarget) onClose()
  }

  function handleRetry() {
    if (userId === null) return
    reset()
    setSelectedCodes(new Set())
    mutate({ userId, strategyId, interval: basisInterval, limit: 50 })
  }

  function handleBulkRegister() {
    bulkAdd.mutate(
      { codes: Array.from(selectedCodes) },
      {
        onSuccess: (result) => {
          if (result.failed.length === 0) {
            // 전부 성공 시 선택 초기화
            setSelectedCodes(new Set())
          }
        },
      },
    )
  }

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 p-4"
      onClick={handleOverlayClick}
      role="dialog"
      aria-modal="true"
      aria-label="전략으로 종목 찾기"
    >
      <div
        className="flex w-full max-w-3xl flex-col rounded-2xl border border-gray-800 bg-gray-900 shadow-xl"
        style={{ maxHeight: '90vh' }}
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <header className="flex flex-shrink-0 flex-wrap items-center justify-between gap-3 border-b border-gray-800 px-6 py-4">
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-white">이 전략으로 종목 찾기</h2>
            <p className="mt-0.5 truncate text-xs text-gray-500">
              Strategy #{strategyId} — KOSPI·KOSDAQ universe · 기준봉 {basisInterval}
            </p>
          </div>
          <div className="flex flex-shrink-0 items-center gap-3">
            <label className="flex items-center gap-2 text-xs text-gray-400">
              기준봉
              <select
                aria-label="기준봉"
                value={basisInterval}
                onChange={(e) => setBasisInterval(e.target.value as CandleInterval)}
                className="rounded border border-gray-700 bg-gray-950 px-2 py-1 text-xs text-white focus:outline-none focus:ring-2 focus:ring-emerald-600"
              >
                {BASIS_INTERVAL_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              onClick={onClose}
              aria-label="닫기"
              className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-600"
            >
              <XIcon />
            </button>
          </div>
        </header>

        {/* 본문 */}
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-6 py-4">
          {/* userId 누락 (setup 미완) */}
          {userId === null && (
            <div className="flex flex-col items-center justify-center gap-3 py-16">
              <p className="text-sm text-yellow-400">
                사용자 설정이 완료되지 않았습니다.
              </p>
              <p className="text-xs text-gray-500">
                설정 화면에서 KIS 키 등록 후 다시 시도해 주세요.
              </p>
            </div>
          )}

          {/* 평가 중 */}
          {userId !== null && isPending && <Spinner />}

          {/* 에러 */}
          {userId !== null && error && !isPending && (
            <div className="flex flex-col items-center justify-center gap-3 py-12">
              <p className="text-sm text-red-400">
                평가 중 오류가 발생했습니다.
              </p>
              <p className="max-w-sm text-center text-xs text-gray-500">
                {error.message}
              </p>
              <button
                type="button"
                onClick={handleRetry}
                className="mt-2 rounded-lg bg-gray-800 px-4 py-2 text-sm text-gray-300 hover:bg-gray-700"
              >
                재시도
              </button>
            </div>
          )}

          {/* 결과 */}
          {data && !isPending && (
            <div className="space-y-4">
              <ScreenerSummary
                universeSize={data.universeSize}
                evaluatedCount={data.evaluatedCount}
                matchedCount={data.matchedCount}
                elapsedMs={data.elapsedMs}
                cacheHit={data.cacheHit}
              />

              {/* skip 매트릭스 */}
              {Object.keys(data.skippedReasons).length > 0 && (
                <div>
                  <p className="mb-1.5 text-xs text-gray-500">skip 사유</p>
                  <ScreenerSkipReasonsBadge reasons={data.skippedReasons} />
                </div>
              )}

              {/* 매칭 테이블 */}
              {data.matches.length > 0 ? (
                <ScreenerMatchTable
                  matches={data.matches}
                  selectedCodes={selectedCodes}
                  onSelectionChange={setSelectedCodes}
                />
              ) : (
                <div className="py-8 text-center text-sm text-gray-500">
                  {hasCandleDataSkips(data.skippedReasons)
                    ? `기준봉 ${basisInterval} 캔들 데이터 준비 중인 종목이 제외되었습니다.`
                    : '매칭된 종목이 없습니다. DSL 조건을 완화해 보세요.'}
                </div>
              )}
            </div>
          )}
        </div>

        {/* 푸터 */}
        <footer className="flex flex-shrink-0 items-center justify-between border-t border-gray-800 px-6 py-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg px-4 py-2 text-sm text-gray-400 hover:bg-gray-800 hover:text-gray-200"
          >
            닫기
          </button>

          <WatchlistBulkRegisterButton
            selectedCodes={selectedCodes}
            isPending={bulkAdd.isPending}
            onRegister={handleBulkRegister}
            lastResult={bulkAdd.data ?? null}
          />
        </footer>
      </div>
    </div>
  )
}
