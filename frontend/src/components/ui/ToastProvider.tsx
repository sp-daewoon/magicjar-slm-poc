/**
 * 자체 Toast 시스템 (react-hot-toast 미사용 — 오프라인 번들 우선 정책)
 *
 * - ToastProvider: Context 제공 + 렌더링
 * - useToast: showToast(message, type) 훅
 * - type: 'success' | 'error' | 'info' | 'warning'
 * - 4초 후 자동 소멸
 * - 최대 5개 동시 표시 (하단 우측 스택)
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react'

// ──────────────────────────────────────────────
// 타입
// ──────────────────────────────────────────────

export type ToastType = 'success' | 'error' | 'info' | 'warning'

export interface ToastItem {
  id: string
  message: string
  type: ToastType
  /** 진입 애니메이션 완료 여부 */
  visible: boolean
}

interface ToastContextValue {
  showToast: (message: string, type?: ToastType) => void
}

// ──────────────────────────────────────────────
// Context
// ──────────────────────────────────────────────

const ToastContext = createContext<ToastContextValue | null>(null)

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext)
  if (!ctx) {
    throw new Error('useToast: ToastProvider 내부에서만 사용 가능합니다.')
  }
  return ctx
}

/**
 * Provider 외부에서도 안전한 useToast 변형 — provider 미존재 시 no-op showToast 반환.
 *
 * 용도: smoke 라우팅 테스트 등 ToastProvider 미장착 환경에서 hook 사용 시 graceful no-op.
 *   사용자 화면 흐름(main.tsx 진입)에는 항상 Provider가 있으므로 영향 0.
 */
export function useOptionalToast(): ToastContextValue {
  const ctx = useContext(ToastContext)
  return ctx ?? { showToast: () => undefined }
}

// ──────────────────────────────────────────────
// 단건 Toast 컴포넌트
// ──────────────────────────────────────────────

const TYPE_STYLES: Record<ToastType, string> = {
  success: 'bg-emerald-900/95 border-emerald-700 text-emerald-100',
  error:   'bg-red-900/95 border-red-700 text-red-100',
  info:    'bg-blue-900/95 border-blue-700 text-blue-100',
  warning: 'bg-amber-900/95 border-amber-700 text-amber-100',
}

const TYPE_ICONS: Record<ToastType, string> = {
  success: '▲',
  error:   '▼',
  info:    '●',
  warning: '⚠',
}

function ToastItem({ item, onClose }: { item: ToastItem; onClose: (id: string) => void }) {
  return (
    <div
      role="alert"
      aria-live="assertive"
      className={[
        'pointer-events-auto flex max-w-sm items-start gap-3 rounded-lg border px-4 py-3 shadow-lg transition-all duration-300',
        TYPE_STYLES[item.type],
        item.visible ? 'translate-x-0 opacity-100' : 'translate-x-8 opacity-0',
      ].join(' ')}
    >
      <span className="mt-0.5 text-base leading-none" aria-hidden>
        {TYPE_ICONS[item.type]}
      </span>
      <span className="flex-1 whitespace-pre-line text-sm leading-snug">{item.message}</span>
      <button
        onClick={() => onClose(item.id)}
        className="ml-2 shrink-0 text-current opacity-60 hover:opacity-100 focus:outline-none"
        aria-label="닫기"
      >
        ✕
      </button>
    </div>
  )
}

// ──────────────────────────────────────────────
// Provider
// ──────────────────────────────────────────────

const TOAST_DURATION_MS = 4000
const MAX_TOASTS = 5

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([])
  const counterRef = useRef(0)

  const removeToast = useCallback((id: string) => {
    // 먼저 visible=false → CSS 애니메이션 후 제거
    setToasts((prev) =>
      prev.map((t) => (t.id === id ? { ...t, visible: false } : t)),
    )
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id))
    }, 310)
  }, [])

  const showToast = useCallback(
    (message: string, type: ToastType = 'info') => {
      const id = `toast-${Date.now()}-${++counterRef.current}`
      const item: ToastItem = { id, message, type, visible: false }

      setToasts((prev) => {
        const next = [...prev, item].slice(-MAX_TOASTS)
        return next
      })

      // 다음 tick에 visible=true → slide-in 트리거
      requestAnimationFrame(() => {
        setToasts((prev) =>
          prev.map((t) => (t.id === id ? { ...t, visible: true } : t)),
        )
      })

      setTimeout(() => removeToast(id), TOAST_DURATION_MS)
    },
    [removeToast],
  )

  // ESC 키로 전체 닫기
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setToasts([])
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {/* 토스트 렌더 영역 — 하단 우측 고정 */}
      <div
        aria-label="알림 목록"
        className="pointer-events-none fixed bottom-6 right-6 z-50 flex flex-col-reverse gap-3"
      >
        {toasts.map((t) => (
          <ToastItem key={t.id} item={t} onClose={removeToast} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}
