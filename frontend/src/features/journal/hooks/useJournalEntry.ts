/**
 * useJournalEntry — GET /api/journal/entries/{id} 단건 query
 *
 * ADR 0072 §6-2 정합
 * entry detail modal에서 사용
 */
import { useQuery } from '@tanstack/react-query'
import { journalApi } from '../api/journalApi'

export function useJournalEntry(id: number | null, userId: number | null) {
  return useQuery({
    queryKey: ['journal', 'entry', id, userId],
    queryFn: () => journalApi.getEntry(id!, userId!),
    enabled: id !== null && userId !== null,
    staleTime: 30_000,
    refetchInterval: false,
  })
}
