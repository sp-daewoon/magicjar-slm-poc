/**
 * JournalEntryDetailModal — 매매일지 entry 단건 상세 모달
 *
 * ADR 0072 §6-2·§6-3 정합:
 * - entry 단건 + payload + notes 표시
 * - 메모 작성 form 포함
 * - ESC 키 닫기 + 배경 클릭 닫기
 */
import { useModalBehavior } from '../../../hooks/useModalBehavior'
import { useJournalEntry } from '../hooks/useJournalEntry'
import { useWriteJournalNote } from '../hooks/useWriteJournalNote'
import { JournalNoteForm } from './JournalNoteForm'
import { resolveJournalInstrumentDisplay } from './journalInstrumentDisplay'

interface JournalEntryDetailModalProps {
  entryId: number
  userId: number
  onClose: () => void
}

function formatKst(isoString: string): string {
  const d = new Date(isoString)
  return d.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function payloadText(payload: Record<string, unknown>, key: string): string | null {
  const value = payload[key]
  if (value === null || value === undefined || value === '') return null
  return String(value)
}

function formatNumber(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  return n.toLocaleString('ko-KR')
}

function formatMoney(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toLocaleString('ko-KR')}원`
}

function tradeSide(payload: Record<string, unknown>): string {
  const side = payloadText(payload, 'side')?.toUpperCase()
  if (side === 'SELL') return '매도'
  if (side === 'BUY') return '매수'
  return '체결'
}

function firstText(payload: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = payloadText(payload, key)
    if (value) return value
  }
  return null
}

function firstValue(payload: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    const value = payload[key]
    if (value !== null && value !== undefined && value !== '') return value
  }
  return null
}

function tradeQuantity(payload: Record<string, unknown>): unknown {
  return firstValue(payload, [
    'executedQty',
    'executedQuantity',
    'filledQuantity',
    'filledQty',
    'quantity',
  ])
}

export function JournalEntryDetailModal({ entryId, userId, onClose }: JournalEntryDetailModalProps) {
  const { data: entry, isLoading, isError } = useJournalEntry(entryId, userId)
  const writeNote = useWriteJournalNote()
  const instrumentDisplay = entry
    ? resolveJournalInstrumentDisplay(entry.instrumentCode, entry.instrumentName)
    : null

  // ESC 닫기 + body scroll lock (공용 훅 — useModalBehavior)
  useModalBehavior({ open: true, onClose })

  function handleNoteSubmit(noteText: string) {
    writeNote.mutate({ userId, journalEntryId: entryId, noteText })
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label="매매일지 상세"
    >
      <div
        className="relative w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-xl border border-gray-700 bg-gray-950 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="sticky top-0 flex items-center justify-between border-b border-gray-800 bg-gray-950 px-5 py-4">
          <h2 className="text-base font-semibold text-gray-200">매매일지 상세</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="닫기"
            className="rounded-md p-1 text-gray-500 hover:bg-gray-800 hover:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div className="px-5 py-4 space-y-4">
          {isLoading && (
            <div className="space-y-3">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-5 animate-pulse rounded bg-gray-800" />
              ))}
            </div>
          )}

          {isError && (
            <p className="text-sm text-red-400">상세 정보를 불러오는 중 오류가 발생했습니다.</p>
          )}

          {entry && (
            <>
              {/* 기본 정보 */}
              <div className="grid grid-cols-2 gap-3 text-sm">
                <div>
                  <span className="text-xs text-gray-500">구분</span>
                  <p className="mt-0.5 font-medium text-gray-200">{tradeSide(entry.payload)}</p>
                </div>
                <div>
                  <span className="text-xs text-gray-500">체결 시각</span>
                  <p className="mt-0.5 text-gray-200">{formatKst(entry.occurredAt)}</p>
                </div>
                {instrumentDisplay && (
                  <div>
                    <span className="text-xs text-gray-500">종목</span>
                    <p className="mt-0.5 text-gray-200">
                      {instrumentDisplay.primary}
                      {instrumentDisplay.code && (
                        <span className="ml-1 text-xs text-gray-500">({instrumentDisplay.code})</span>
                      )}
                    </p>
                  </div>
                )}
                <div>
                  <span className="text-xs text-gray-500">실현 손익</span>
                  <p
                    className={`mt-0.5 font-mono font-semibold ${
                      Number(entry.realizedPnl ?? entry.payload.realizedPnl ?? 0) > 0
                        ? 'text-emerald-400'
                        : Number(entry.realizedPnl ?? entry.payload.realizedPnl ?? 0) < 0
                          ? 'text-red-400'
                          : 'text-gray-400'
                    }`}
                  >
                    {formatMoney(entry.realizedPnl ?? entry.payload.realizedPnl)}
                  </p>
                </div>
              </div>

              {/* 요약 */}
              <div>
                <span className="text-xs text-gray-500">내용</span>
                <p className="mt-1 text-sm text-gray-200">{entry.summary}</p>
              </div>

              {/* 체결 정보 */}
              <div className="rounded-md border border-gray-800 bg-gray-900/70 p-3">
                <span className="text-xs text-gray-500">체결 정보</span>
                <div className="mt-2 grid grid-cols-2 gap-3 text-sm">
                  <div>
                    <span className="text-xs text-gray-600">수량</span>
                    <p className="mt-0.5 text-gray-200">
                      {`${formatNumber(tradeQuantity(entry.payload))}주`}
                    </p>
                  </div>
                  <div>
                    <span className="text-xs text-gray-600">체결가</span>
                    <p className="mt-0.5 text-gray-200">{formatNumber(entry.payload.executedPrice)}원</p>
                  </div>
                  {entry.payload.avgBuyPrice !== null && entry.payload.avgBuyPrice !== undefined && (
                    <div>
                      <span className="text-xs text-gray-600">매도 기준 평단</span>
                      <p className="mt-0.5 text-gray-200">{formatNumber(entry.payload.avgBuyPrice)}원</p>
                    </div>
                  )}
                  {entry.payload.closedQuantity !== null && entry.payload.closedQuantity !== undefined && (
                    <div>
                      <span className="text-xs text-gray-600">청산 수량</span>
                      <p className="mt-0.5 text-gray-200">{formatNumber(entry.payload.closedQuantity)}주</p>
                    </div>
                  )}
                </div>
              </div>

              {/* 전략 */}
              {(firstText(entry.payload, ['strategyDisplayName', 'strategyNameKo', 'strategyName']) ||
                payloadText(entry.payload, 'strategyEntryDsl') ||
                payloadText(entry.payload, 'strategyExitDsl')) && (
                <div className="rounded-md border border-gray-800 bg-gray-900/70 p-3">
                  <span className="text-xs text-gray-500">전략</span>
                  <div className="mt-2 text-sm text-gray-200">
                    <p className="font-medium">
                      {firstText(entry.payload, ['strategyDisplayName', 'strategyNameKo', 'strategyName']) ?? '-'}
                      {payloadText(entry.payload, 'strategyTimeframe') && (
                        <span className="ml-2 text-xs font-normal text-gray-500">
                          {payloadText(entry.payload, 'strategyTimeframe')}
                        </span>
                      )}
                    </p>
                    {payloadText(entry.payload, 'strategyEntryDsl') && (
                      <pre className="mt-2 overflow-x-auto rounded border border-gray-800 bg-gray-950 p-2 text-xs text-gray-400">
                        {payloadText(entry.payload, 'strategyEntryDsl')}
                      </pre>
                    )}
                    {payloadText(entry.payload, 'strategyExitDsl') && (
                      <pre className="mt-2 overflow-x-auto rounded border border-gray-800 bg-gray-950 p-2 text-xs text-gray-400">
                        {payloadText(entry.payload, 'strategyExitDsl')}
                      </pre>
                    )}
                  </div>
                </div>
              )}

              {/* 시그널 근거 */}
              {(payloadText(entry.payload, 'signalReason') ||
                payloadText(entry.payload, 'ruleSignal') ||
                payloadText(entry.payload, 'signalPrice')) && (
                <div className="rounded-md border border-gray-800 bg-gray-900/70 p-3">
                  <span className="text-xs text-gray-500">시그널 근거</span>
                  <div className="mt-2 space-y-2 text-sm">
                    {payloadText(entry.payload, 'signalReason') && (
                      <p className="text-gray-200">{payloadText(entry.payload, 'signalReason')}</p>
                    )}
                    <div className="grid grid-cols-2 gap-3">
                      {payloadText(entry.payload, 'ruleSignal') && (
                        <div>
                          <span className="text-xs text-gray-600">룰</span>
                          <p className="mt-0.5 text-gray-300">{payloadText(entry.payload, 'ruleSignal')}</p>
                        </div>
                      )}
                      {payloadText(entry.payload, 'signalPrice') && (
                        <div>
                          <span className="text-xs text-gray-600">시그널 가격</span>
                          <p className="mt-0.5 text-gray-300">{formatNumber(entry.payload.signalPrice)}원</p>
                        </div>
                      )}
                      {payloadText(entry.payload, 'signalStrength') && (
                        <div>
                          <span className="text-xs text-gray-600">강도</span>
                          <p className="mt-0.5 text-gray-300">{payloadText(entry.payload, 'signalStrength')}</p>
                        </div>
                      )}
                      {payloadText(entry.payload, 'signalInterval') && (
                        <div>
                          <span className="text-xs text-gray-600">봉</span>
                          <p className="mt-0.5 text-gray-300">{payloadText(entry.payload, 'signalInterval')}</p>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {/* payload */}
              <div>
                <details>
                  <summary className="cursor-pointer text-xs text-gray-500">원본 페이로드</summary>
                  <pre className="mt-1 max-h-36 overflow-y-auto rounded-md border border-gray-800 bg-gray-900 p-3 text-xs text-gray-400">
                    {JSON.stringify(entry.payload, null, 2)}
                  </pre>
                </details>
              </div>

              {/* 메모 목록 */}
              {entry.notes.length > 0 && (
                <div>
                  <span className="text-xs text-gray-500">메모 ({entry.notes.length}건)</span>
                  <div className="mt-2 space-y-2">
                    {entry.notes.map((note) => (
                      <div key={note.id} className="rounded-md border border-gray-800 bg-gray-900 p-3">
                        <p className="text-sm text-gray-300">{note.noteText}</p>
                        <p className="mt-1 text-xs text-gray-600">{formatKst(note.createdAt)}</p>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 메모 작성 */}
              <div>
                <span className="text-xs text-gray-500">메모 작성</span>
                <div className="mt-2">
                  <JournalNoteForm
                    onSubmit={handleNoteSubmit}
                    isSubmitting={writeNote.isPending}
                  />
                </div>
                {writeNote.isError && (
                  <p className="mt-1 text-xs text-red-400">메모 저장에 실패했습니다.</p>
                )}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
