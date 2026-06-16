interface WatchlistToggleButtonProps {
  code: string
  name?: string | null
  isWatched: boolean
  isPending?: boolean
  disabled?: boolean
  compact?: boolean
  iconOnly?: boolean
  onAdd: (code: string, name?: string | null) => void
  onRemove: (code: string, name?: string | null) => void
}

export function WatchlistToggleButton({
  code,
  name,
  isWatched,
  isPending = false,
  disabled = false,
  compact = false,
  iconOnly = false,
  onAdd,
  onRemove,
}: WatchlistToggleButtonProps) {
  const displayName = name && name !== code ? name : code
  const buttonLabel = (() => {
    if (iconOnly) return isWatched ? '★' : '☆'
    if (isPending) return '처리 중...'
    return isWatched ? '★ 관심해제' : '☆ 관심등록'
  })()

  function handleClick(e: React.MouseEvent<HTMLButtonElement>) {
    e.stopPropagation()
    if (disabled || isPending) return
    if (isWatched) {
      onRemove(code, name)
    } else {
      onAdd(code, name)
    }
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={disabled || isPending}
      aria-pressed={isWatched}
      aria-label={
        isWatched
          ? `${displayName} 관심종목 해제`
          : `${displayName} 관심종목 등록`
      }
      title={
        isWatched
          ? '관심해제 시 내 관심종목 등록 기록만 삭제됩니다'
          : '내 관심종목에 등록합니다'
      }
      className={[
        'inline-flex shrink-0 items-center justify-center rounded-lg border font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950 disabled:cursor-not-allowed disabled:opacity-50',
        iconOnly
          ? 'h-6 w-6 px-0 text-sm leading-none'
          : compact
            ? 'h-8 px-2.5 text-xs'
            : 'h-9 px-3 text-sm',
        isWatched
          ? 'border-amber-500/40 bg-amber-950/30 text-amber-200 hover:border-amber-400/70 hover:bg-amber-900/40'
          : 'border-gray-700 bg-gray-900 text-gray-300 hover:border-gray-600 hover:bg-gray-800 hover:text-white',
      ].join(' ')}
    >
      {buttonLabel}
    </button>
  )
}
