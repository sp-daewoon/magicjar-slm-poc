import { FormEvent, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createMarketCalendarEvent,
  deleteMarketCalendarEvent,
  fetchMarketCalendar,
  updateMarketCalendarEvent,
  type MarketCalendarEvent,
  type MarketCalendarEventRequest,
} from '../../../api/marketCalendar'
import type { InstrumentSearchResultDto } from '../../../api/instrument'
import { InstrumentSearchInput } from '../../../components/InstrumentSearchInput'

const EVENT_TYPE_OPTIONS = [
  { code: 'IPO', label: 'IPO' },
  { code: 'EARNINGS', label: '실적' },
  { code: 'DIVIDEND', label: '배당' },
  { code: 'LOCKUP', label: '보호예수' },
  { code: 'MACRO', label: '거시' },
  { code: 'FOMC', label: 'FOMC' },
  { code: 'CUSTOM', label: '기타' },
] as const

const EVENT_TYPE_META: Record<string, { label: string; className: string }> = {
  IPO: {
    label: 'IPO',
    className: 'border-emerald-500/40 bg-emerald-950/50 text-emerald-200',
  },
  EARNINGS: {
    label: '실적',
    className: 'border-blue-500/40 bg-blue-950/50 text-blue-200',
  },
  DIVIDEND: {
    label: '배당',
    className: 'border-amber-500/40 bg-amber-950/50 text-amber-200',
  },
  LOCKUP: {
    label: '보호예수',
    className: 'border-rose-500/40 bg-rose-950/50 text-rose-200',
  },
  MACRO: {
    label: '거시',
    className: 'border-sky-500/40 bg-sky-950/50 text-sky-200',
  },
  FOMC: {
    label: 'FOMC',
    className: 'border-violet-500/40 bg-violet-950/50 text-violet-200',
  },
  CUSTOM: {
    label: '기타',
    className: 'border-gray-600 bg-gray-800 text-gray-200',
  },
}

const MARKET_OPTIONS = ['KRX', 'US', 'GLOBAL'] as const

interface EventFormState {
  date: string
  market: string
  eventType: string
  title: string
  instrumentCode: string
  instrumentInput: string
  description: string
}

function formatIsoDate(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function parseIsoDate(isoDate: string): Date {
  const [year, month, day] = isoDate.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function addDays(date: Date, amount: number): Date {
  const next = new Date(date)
  next.setDate(next.getDate() + amount)
  return next
}

function addMonths(date: Date, amount: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + amount, 1)
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function startOfWeek(date: Date): Date {
  return addDays(date, -date.getDay())
}

function monthLabel(date: Date): string {
  return `${date.getFullYear()}년 ${date.getMonth() + 1}월`
}

function dayCellLabel(date: Date): string {
  if (date.getDate() === 1) return `${date.getMonth() + 1}월 1일`
  return `${date.getDate()}일`
}

function instrumentDisplay(result: InstrumentSearchResultDto): string {
  return `${result.name} (${result.code})`
}

function extractInstrumentCode(value: string): string | null {
  const trimmed = value.trim()
  const labelMatch = trimmed.match(/\((\d{6})\)\s*$/)
  if (labelMatch) return labelMatch[1]
  return /^\d{6}$/.test(trimmed) ? trimmed : null
}

function eventTypeLabel(eventType: string): string {
  return EVENT_TYPE_META[eventType]?.label ?? eventType
}

function eventTypeClass(eventType: string): string {
  return EVENT_TYPE_META[eventType]?.className ?? EVENT_TYPE_META.CUSTOM.className
}

function toRequest(form: EventFormState): MarketCalendarEventRequest {
  return {
    date: form.date,
    market: form.market.trim() || 'KRX',
    eventType: form.eventType.trim() || 'CUSTOM',
    title: form.title.trim(),
    instrumentCode: form.instrumentCode.trim() || null,
    description: form.description.trim() || null,
  }
}

function makeEmptyForm(date: string): EventFormState {
  return {
    date,
    market: 'KRX',
    eventType: 'IPO',
    title: '',
    instrumentCode: '',
    instrumentInput: '',
    description: '',
  }
}

const WEEKDAY_LABELS = ['일', '월', '화', '수', '목', '금', '토']

export function MarketCalendarPage() {
  const today = formatIsoDate(new Date())
  const [viewMonth, setViewMonth] = useState(() => startOfMonth(parseIsoDate(today)))
  const [selectedDate, setSelectedDate] = useState(today)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<EventFormState>(() => makeEmptyForm(today))
  const queryClient = useQueryClient()

  const calendarFrame = useMemo(() => {
    const monthStart = startOfMonth(viewMonth)
    const gridStart = startOfWeek(monthStart)
    const gridEnd = addDays(gridStart, 41)
    return {
      monthStart,
      gridStart,
      gridEnd,
      from: formatIsoDate(gridStart),
      to: formatIsoDate(gridEnd),
    }
  }, [viewMonth])

  const gridDates = useMemo(() => {
    const dates: Date[] = []
    let cursor = calendarFrame.gridStart
    while (cursor <= calendarFrame.gridEnd) {
      dates.push(cursor)
      cursor = addDays(cursor, 1)
    }
    return dates
  }, [calendarFrame.gridEnd, calendarFrame.gridStart])

  const { data, isLoading, isError } = useQuery({
    queryKey: ['marketCalendar', calendarFrame.from, calendarFrame.to],
    queryFn: () =>
      fetchMarketCalendar({
        from: calendarFrame.from,
        to: calendarFrame.to,
      }),
    staleTime: 30_000,
  })

  const createMutation = useMutation({
    mutationFn: createMarketCalendarEvent,
    onSuccess: async (_event, request) => {
      await queryClient.invalidateQueries({ queryKey: ['marketCalendar'] })
      setForm(makeEmptyForm(request.date))
      setEditingId(null)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, request }: { id: number; request: MarketCalendarEventRequest }) =>
      updateMarketCalendarEvent(id, request),
    onSuccess: async (_event, variables) => {
      await queryClient.invalidateQueries({ queryKey: ['marketCalendar'] })
      setForm(makeEmptyForm(variables.request.date))
      setEditingId(null)
    },
  })

  const deleteMutation = useMutation({
    mutationFn: deleteMarketCalendarEvent,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['marketCalendar'] })
      if (editingId !== null) {
        setEditingId(null)
        setForm(makeEmptyForm(selectedDate))
      }
    },
  })

  const daysByDate = useMemo(() => {
    const map = new Map<string, NonNullable<typeof data>['days'][number]>()
    data?.days.forEach((day) => map.set(day.date, day))
    return map
  }, [data])

  const selectedDay = daysByDate.get(selectedDate)
  const selectedEvents = selectedDay?.events ?? []
  const isSaving = createMutation.isPending || updateMutation.isPending

  function selectDate(date: string) {
    setSelectedDate(date)
    setForm((prev) => ({ ...prev, date }))
  }

  function moveMonth(amount: number) {
    const nextMonth = addMonths(calendarFrame.monthStart, amount)
    const nextDate = formatIsoDate(nextMonth)
    setViewMonth(nextMonth)
    selectDate(nextDate)
  }

  function moveToday() {
    setViewMonth(startOfMonth(parseIsoDate(today)))
    selectDate(today)
  }

  function beginEdit(event: MarketCalendarEvent) {
    setEditingId(event.id)
    setSelectedDate(event.date)
    setForm({
      date: event.date,
      market: event.market,
      eventType: event.eventType,
      title: event.title,
      instrumentCode: event.instrumentCode ?? '',
      instrumentInput: event.instrumentCode ?? '',
      description: event.description ?? '',
    })
  }

  function handleFormDateChange(date: string) {
    if (!date) {
      setSelectedDate(date)
      setForm((prev) => ({ ...prev, date }))
      return
    }
    setViewMonth(startOfMonth(parseIsoDate(date)))
    selectDate(date)
  }

  function handleInstrumentRawChange(value: string) {
    setForm((prev) => ({
      ...prev,
      instrumentInput: value,
      instrumentCode: extractInstrumentCode(value) ?? '',
    }))
  }

  function handleInstrumentSelect(result: InstrumentSearchResultDto) {
    setForm((prev) => ({
      ...prev,
      instrumentCode: result.code,
      instrumentInput: instrumentDisplay(result),
    }))
  }

  function cancelEdit() {
    setEditingId(null)
    setForm(makeEmptyForm(selectedDate))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const request = toRequest(form)
    if (!request.title) return
    if (editingId === null) {
      await createMutation.mutateAsync(request)
      return
    }
    await updateMutation.mutateAsync({ id: editingId, request })
  }

  async function handleDelete(eventId: number) {
    if (!window.confirm('이 일정을 삭제할까요?')) return
    await deleteMutation.mutateAsync(eventId)
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6 text-gray-100">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold">시장 캘린더</h1>
          <p className="mt-0.5 text-sm text-gray-500">KRX 영업일 · 시장 이벤트</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => moveMonth(-1)}
            className="h-9 rounded-lg border border-gray-700 px-3 text-sm text-gray-200 transition-colors hover:border-gray-500 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
            aria-label="이전 달"
          >
            ‹
          </button>
          <div className="min-w-36 text-center text-xl font-semibold">
            {monthLabel(calendarFrame.monthStart)}
          </div>
          <button
            type="button"
            onClick={() => moveMonth(1)}
            className="h-9 rounded-lg border border-gray-700 px-3 text-sm text-gray-200 transition-colors hover:border-gray-500 hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-blue-400"
            aria-label="다음 달"
          >
            ›
          </button>
          <button
            type="button"
            onClick={moveToday}
            className="h-9 rounded-lg bg-blue-600 px-3 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400"
          >
            오늘
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <section aria-label="월간 시장 캘린더" className="min-w-0">
          <div className="grid grid-cols-7 border-y border-gray-800 bg-gray-900/70 text-center text-sm font-semibold text-gray-300">
            {WEEKDAY_LABELS.map((weekday) => (
              <div key={weekday} className="px-2 py-2">
                {weekday}
              </div>
            ))}
          </div>

          <div className="grid grid-cols-7 overflow-hidden rounded-b-lg border-l border-gray-800 bg-gray-950">
            {gridDates.map((date) => {
              const isoDate = formatIsoDate(date)
              const day = daysByDate.get(isoDate)
              const events = day?.events ?? []
              const isToday = isoDate === today
              const isClosed = day ? !day.isBusinessDay : date.getDay() === 0 || date.getDay() === 6
              const isCurrentMonth = date.getMonth() === calendarFrame.monthStart.getMonth()
              const isSelected = isoDate === selectedDate

              return (
                <div
                  key={isoDate}
                  data-testid={`calendar-day-${isoDate}`}
                  role="button"
                  tabIndex={0}
                  aria-pressed={isSelected}
                  onClick={() => selectDate(isoDate)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      selectDate(isoDate)
                    }
                  }}
                  className={[
                    'min-h-[118px] cursor-pointer border-b border-r border-gray-800 p-2 outline-none transition-colors focus-visible:ring-2 focus-visible:ring-blue-400',
                    isCurrentMonth ? 'bg-gray-950 text-gray-100' : 'bg-gray-950/70 text-gray-600',
                    isSelected ? 'ring-2 ring-inset ring-blue-500' : '',
                  ].join(' ')}
                >
                  <div className="flex w-full items-start justify-between gap-2 text-left">
                    <span
                      className={[
                        'flex min-h-7 min-w-7 items-center justify-center rounded-lg px-1 text-sm font-semibold',
                        isToday ? 'bg-blue-600 text-white' : '',
                        !isToday && !isCurrentMonth ? 'text-gray-600' : '',
                      ].join(' ')}
                    >
                      {dayCellLabel(date)}
                    </span>
                    {isClosed && (
                      <span className="truncate rounded bg-red-950/70 px-1.5 py-0.5 text-[10px] text-red-200">
                        {day?.holidayName ?? '휴장'}
                      </span>
                    )}
                  </div>

                  <div className="mt-2 space-y-1">
                    {events.slice(0, 3).map((calendarEvent) => (
                      <button
                        key={calendarEvent.id}
                        type="button"
                        onClick={() => beginEdit(calendarEvent)}
                        className={[
                          'block w-full rounded-md border px-2 py-1 text-left text-[11px] leading-tight transition-opacity hover:opacity-80 focus:outline-none focus:ring-2 focus:ring-blue-400',
                          eventTypeClass(calendarEvent.eventType),
                          !isCurrentMonth ? 'opacity-55' : '',
                        ].join(' ')}
                      >
                        <span className="mr-1 font-semibold">
                          {eventTypeLabel(calendarEvent.eventType)}
                        </span>
                        <span className="truncate align-bottom">{calendarEvent.title}</span>
                      </button>
                    ))}
                    {events.length > 3 && (
                      <div className="text-[11px] text-gray-500">+{events.length - 3}</div>
                    )}
                  </div>
                </div>
              )
            })}
          </div>

          {isLoading && (
            <div role="status" className="mt-3 text-sm text-gray-500">
              캘린더 로딩 중...
            </div>
          )}
          {isError && (
            <div role="alert" className="mt-3 text-sm text-red-400">
              캘린더를 불러오는 중 오류가 발생했습니다.
            </div>
          )}
        </section>

        <aside className="space-y-4">
          <section className="rounded-lg border border-gray-800 bg-gray-900/60 p-4">
            <div className="mb-3 flex items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-gray-100">
                {editingId === null ? '일정 추가' : '일정 수정'}
              </h2>
              {editingId !== null && (
                <button
                  type="button"
                  onClick={cancelEdit}
                  className="rounded-md px-2 py-1 text-xs text-gray-400 hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400"
                >
                  취소
                </button>
              )}
            </div>

            <form onSubmit={handleSubmit} className="space-y-3">
              <label className="block">
                <span className="mb-1 block text-xs font-medium text-gray-400">날짜</span>
                <input
                  type="date"
                  value={form.date}
                  onChange={(e) => handleFormDateChange(e.target.value)}
                  className="h-10 w-full rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-gray-100 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </label>

              <div className="grid grid-cols-2 gap-2">
                <label className="block">
                  <span className="mb-1 block text-xs font-medium text-gray-400">시장</span>
                  <select
                    value={form.market}
                    onChange={(e) => setForm((prev) => ({ ...prev, market: e.target.value }))}
                    className="h-10 w-full rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-gray-100 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  >
                    {MARKET_OPTIONS.map((market) => (
                      <option key={market} value={market}>
                        {market}
                      </option>
                    ))}
                  </select>
                </label>

                <label className="block">
                  <span className="mb-1 block text-xs font-medium text-gray-400">유형</span>
                  <input
                    list="market-event-types"
                    value={form.eventType}
                    onChange={(e) =>
                      setForm((prev) => ({ ...prev, eventType: e.target.value }))
                    }
                    maxLength={32}
                    className="h-10 w-full rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-gray-100 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                  />
                  <datalist id="market-event-types">
                    {EVENT_TYPE_OPTIONS.map((option) => (
                      <option key={option.code} value={option.code}>
                        {option.label}
                      </option>
                    ))}
                  </datalist>
                </label>
              </div>

              <label className="block">
                <span className="mb-1 block text-xs font-medium text-gray-400">제목</span>
                <input
                  type="text"
                  value={form.title}
                  onChange={(e) => setForm((prev) => ({ ...prev, title: e.target.value }))}
                  maxLength={120}
                  className="h-10 w-full rounded-lg border border-gray-700 bg-gray-950 px-3 text-sm text-gray-100 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </label>

              <label className="block">
                <span className="mb-1 block text-xs font-medium text-gray-400">종목</span>
                <InstrumentSearchInput
                  value={form.instrumentInput}
                  onRawChange={handleInstrumentRawChange}
                  onSelect={handleInstrumentSelect}
                  placeholder="종목명 또는 코드 검색"
                />
              </label>

              <label className="block">
                <span className="mb-1 block text-xs font-medium text-gray-400">메모</span>
                <textarea
                  value={form.description}
                  onChange={(e) =>
                    setForm((prev) => ({ ...prev, description: e.target.value }))
                  }
                  rows={3}
                  maxLength={2000}
                  className="w-full resize-none rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </label>

              <button
                type="submit"
                disabled={isSaving || !form.title.trim()}
                className="h-10 w-full rounded-lg bg-blue-600 text-sm font-semibold text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400 disabled:cursor-not-allowed disabled:bg-gray-700 disabled:text-gray-400"
              >
                {isSaving ? '저장 중...' : editingId === null ? '추가' : '수정'}
              </button>
            </form>
          </section>

          <section className="rounded-lg border border-gray-800 bg-gray-900/60 p-4">
            <div className="mb-3 flex items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-gray-100">{selectedDate}</h2>
              {selectedDay && !selectedDay.isBusinessDay && (
                <span className="rounded bg-red-950/70 px-2 py-0.5 text-xs text-red-200">
                  {selectedDay.holidayName ?? '휴장'}
                </span>
              )}
            </div>

            {selectedEvents.length === 0 ? (
              <p className="text-sm text-gray-500">등록된 일정 없음</p>
            ) : (
              <div className="space-y-2">
                {selectedEvents.map((event) => (
                  <div key={event.id} className="rounded-lg border border-gray-800 bg-gray-950 p-3">
                    <div className="mb-2 flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-1">
                          <span
                            className={[
                              'rounded-md border px-1.5 py-0.5 text-[11px] font-semibold',
                              eventTypeClass(event.eventType),
                            ].join(' ')}
                          >
                            {eventTypeLabel(event.eventType)}
                          </span>
                          <span className="rounded-md bg-gray-800 px-1.5 py-0.5 text-[11px] text-gray-300">
                            {event.market}
                          </span>
                        </div>
                        <p className="mt-2 truncate text-sm font-semibold text-gray-100">
                          {event.title}
                        </p>
                        {event.instrumentCode && (
                          <p className="mt-1 text-xs text-gray-500">{event.instrumentCode}</p>
                        )}
                      </div>
                      <div className="flex shrink-0 gap-1">
                        <button
                          type="button"
                          onClick={() => beginEdit(event)}
                          className="rounded-md px-2 py-1 text-xs text-gray-400 hover:bg-gray-800 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400"
                        >
                          수정
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(event.id)}
                          disabled={deleteMutation.isPending}
                          className="rounded-md px-2 py-1 text-xs text-red-300 hover:bg-red-950/50 focus:outline-none focus:ring-2 focus:ring-red-400 disabled:cursor-not-allowed disabled:text-gray-600"
                        >
                          삭제
                        </button>
                      </div>
                    </div>
                    {event.description && (
                      <p className="whitespace-pre-wrap text-xs leading-5 text-gray-400">
                        {event.description}
                      </p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </section>
        </aside>
      </div>
    </div>
  )
}
