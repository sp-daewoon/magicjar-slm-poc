import { useCallback, useMemo, useState } from 'react'
import axios from 'axios'
import { useAddWatchlist, useRemoveWatchlist, useWatchlist } from '../../api/hooks/useWatchlist'
import type { WatchlistErrorResponse } from '../../api/watchlist'
import { useOptionalToast } from '../ui/ToastProvider'

function displayNameOf(code: string, name?: string | null): string {
  return name && name !== code ? `${name}(${code})` : code
}

function resolveWatchlistError(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as Partial<WatchlistErrorResponse> | undefined
    return data?.message ?? fallback
  }
  return fallback
}

export function useWatchlistToggle() {
  const { data: entries, isLoading, isError } = useWatchlist()
  const { mutate: addWatchlist, isPending: isAddPending } = useAddWatchlist()
  const { mutate: removeWatchlist, isPending: isRemovePending } = useRemoveWatchlist()
  const { showToast } = useOptionalToast()
  const [pendingCode, setPendingCode] = useState<string | null>(null)

  const watchlistCodes = useMemo(
    () => new Set((entries ?? []).map((entry) => entry.code)),
    [entries],
  )

  const addToWatchlist = useCallback(
    (code: string, name?: string | null) => {
      setPendingCode(code)
      addWatchlist(
        { code },
        {
          onSuccess: () => {
            showToast(`${displayNameOf(code, name)} 관심종목 등록됨`, 'success')
          },
          onError: (err) => {
            showToast(resolveWatchlistError(err, '관심종목 등록 중 오류가 발생했습니다'), 'error')
          },
          onSettled: () => setPendingCode(null),
        },
      )
    },
    [addWatchlist, showToast],
  )

  const removeFromWatchlist = useCallback(
    (code: string, name?: string | null) => {
      setPendingCode(code)
      removeWatchlist(
        code,
        {
          onSuccess: () => {
            showToast(`${displayNameOf(code, name)} 관심종목에서 해제됨`, 'info')
          },
          onError: (err) => {
            showToast(resolveWatchlistError(err, '관심종목 해제 중 오류가 발생했습니다'), 'error')
          },
          onSettled: () => setPendingCode(null),
        },
      )
    },
    [removeWatchlist, showToast],
  )

  const isWatched = useCallback(
    (code: string) => watchlistCodes.has(code),
    [watchlistCodes],
  )

  return {
    entries: entries ?? [],
    watchlistCodes,
    isWatched,
    isLoading,
    isError,
    isMutating: isAddPending || isRemovePending,
    pendingCode,
    addToWatchlist,
    removeFromWatchlist,
  }
}
