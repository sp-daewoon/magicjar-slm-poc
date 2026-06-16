/**
 * useWriteJournalNote — POST /api/journal/notes mutation
 *
 * ADR 0072 §6-3 정합
 * 성공 시 해당 entry query 무효화 → 메모 자동 갱신
 */
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { journalApi, type JournalNoteRequest, type JournalNoteDto } from '../api/journalApi'

export function useWriteJournalNote() {
  const queryClient = useQueryClient()

  return useMutation<JournalNoteDto, Error, JournalNoteRequest>({
    mutationFn: (req) => journalApi.writeNote(req),
    onSuccess: (_data, variables) => {
      // entry detail 캐시 무효화 → 메모 포함 재조회
      queryClient.invalidateQueries({
        queryKey: ['journal', 'entry', variables.journalEntryId],
      })
      // list도 무효화 (메모 indicator 갱신)
      queryClient.invalidateQueries({
        queryKey: ['journal', 'entries'],
      })
    },
  })
}
