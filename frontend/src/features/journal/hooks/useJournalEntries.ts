/**
 * useJournalEntries — GET /api/journal/entries 페이징 query
 *
 * ADR 0072 §7 STOMP 0 LOCK 정합 — REST polling (staleTime 30s)
 * 마운트 폴링 0 (refetchInterval 0)
 * mock 합성 0 — userId null 시 setup 안내 graceful
 */
import { useQuery, keepPreviousData } from '@tanstack/react-query'
import { journalApi, type JournalEventType } from '../api/journalApi'

interface UseJournalEntriesParams {
  userId: number | null
  from?: string
  to?: string
  instrumentCode?: string
  instrumentQuery?: string
  eventType?: JournalEventType
  page?: number
  size?: number
}

export function useJournalEntries({
  userId,
  from,
  to,
  instrumentCode,
  instrumentQuery,
  eventType,
  page = 0,
  size = 20,
}: UseJournalEntriesParams) {
  return useQuery({
    queryKey: ['journal', 'entries', { userId, from, to, instrumentCode, instrumentQuery, eventType, page, size }],
    queryFn: () =>
      journalApi.listEntries({
        userId: userId!,
        from,
        to,
        instrumentCode,
        instrumentQuery,
        eventType,
        page,
        size,
      }),
    enabled: userId !== null,
    staleTime: 30_000,
    refetchInterval: false,
    placeholderData: keepPreviousData,
  })
}
