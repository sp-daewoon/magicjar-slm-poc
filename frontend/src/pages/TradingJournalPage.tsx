/**
 * TradingJournalPage — /journal 라우트
 *
 * ADR 0072 §2-6 F-1 + spec §3-3 Phase 3 정합:
 * - filter bar + JournalEntryList + 페이지네이션
 * - 행 클릭 → JournalEntryDetailModal
 * - userId: useOnboardingStore.registeredUserId (null 시 setup 안내)
 * - mock 합성 0 — 빈 결과 "매매일지가 비어있습니다" 명시
 */
import { useState } from 'react'
import { useOnboardingStore } from '../stores/onboarding'
import {
  JournalEntryFilterBar,
  type JournalEntryFilterState,
} from '../features/journal/components/JournalEntryFilterBar'
import { JournalEntryList } from '../features/journal/components/JournalEntryList'
import { JournalEntryDetailModal } from '../features/journal/components/JournalEntryDetailModal'
import { useJournalEntries } from '../features/journal/hooks/useJournalEntries'
import type { TradingJournalEntryDto } from '../features/journal/api/journalApi'

const DEFAULT_FILTER_LOOKBACK_DAYS = 7

export function createDefaultJournalFilter(today = new Date()): JournalEntryFilterState {
  return {
    from: formatJournalDate(today),
    to: formatJournalDate(addDays(today, -DEFAULT_FILTER_LOOKBACK_DAYS)),
    instrumentCode: '',
    instrumentInput: '',
  }
}

export function normalizeJournalDateRange(from: string, to: string): { from: string; to: string } {
  if (!from && !to) return { from: '', to: '' }
  if (!from) return { from: '', to: toKstDayEnd(to) }
  if (!to) return { from: toKstDayStart(from), to: '' }

  const oldest = from <= to ? from : to
  const newest = from <= to ? to : from
  return {
    from: toKstDayStart(oldest),
    to: toKstDayEnd(newest),
  }
}

function toKstDayStart(date: string): string {
  return `${date}T00:00:00+09:00`
}

function toKstDayEnd(date: string): string {
  return `${date}T23:59:59+09:00`
}

function formatJournalDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date)
  next.setDate(next.getDate() + days)
  return next
}

export function TradingJournalPage() {
  // ADR 0089 Phase 2 — registeredUserId now number, no conversion needed
  const userId = useOnboardingStore((s) => s.registeredUserId)

  const [filter, setFilter] = useState<JournalEntryFilterState>(() => createDefaultJournalFilter())
  const [page, setPage] = useState(0)
  const [selectedEntry, setSelectedEntry] = useState<TradingJournalEntryDto | null>(null)

  // 필터 변경 시 page 리셋
  function handleFilterChange(next: JournalEntryFilterState) {
    setFilter(next)
    setPage(0)
  }

  function handleFilterReset() {
    setFilter(createDefaultJournalFilter())
    setPage(0)
  }

  const queryDateRange = normalizeJournalDateRange(filter.from, filter.to)

  const { data, isLoading, isError } = useJournalEntries({
    userId,
    from: queryDateRange.from || undefined,
    to: queryDateRange.to || undefined,
    instrumentCode: filter.instrumentCode || undefined,
    instrumentQuery: filter.instrumentCode ? undefined : filter.instrumentInput.trim() || undefined,
    page,
    size: 20,
  })

  // userId 미등록 → setup 안내
  if (!userId) {
    return (
      <div className="mx-auto max-w-7xl px-4 py-10">
        <div className="rounded-lg border border-yellow-800 bg-yellow-900/20 p-6 text-center">
          <p className="text-sm text-yellow-300">사용자 설정이 완료되지 않았습니다.</p>
          <p className="mt-1 text-xs text-yellow-400">
            <a href="/setup" className="underline hover:text-yellow-200">
              설정 마법사
            </a>
            에서 계좌를 등록해 주세요.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 페이지 헤더 */}
      <div className="mb-5">
        <h1 className="text-xl font-bold text-gray-100">매매일지</h1>
        <p className="mt-0.5 text-sm text-gray-500">
          실제 매수·매도 체결과 전략 근거를 남기는 복기장
        </p>
      </div>

      {/* 필터 바 */}
      <div className="mb-4">
        <JournalEntryFilterBar
          filter={filter}
          onChange={handleFilterChange}
          onReset={handleFilterReset}
        />
      </div>

      {/* 총 건수 */}
      {data && (
        <p className="mb-2 text-xs text-gray-500">
          총 {data.totalElements.toLocaleString('ko-KR')}건
        </p>
      )}

      {/* 목록 */}
      <JournalEntryList
        entries={data?.content ?? []}
        isLoading={isLoading}
        isError={isError}
        page={page}
        totalPages={data?.totalPages ?? 0}
        onPageChange={setPage}
        onEntryClick={setSelectedEntry}
      />

      {/* 상세 모달 */}
      {selectedEntry && (
        <JournalEntryDetailModal
          entryId={selectedEntry.id}
          userId={userId}
          onClose={() => setSelectedEntry(null)}
        />
      )}
    </div>
  )
}
