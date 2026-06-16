/**
 * KisRealCredentialBanner — REAL 자격증명 미등록 경고 배너 (backlog §58 · ADR 0077 K-1)
 *
 * 사유:
 *   ADR 0077 J-1 hotfix(v3.6.11)에서 backend는 REAL 자격증명 미등록 시 WS 작동
 *   불가 + `WatchlistIngestionWorker.onStartup()` WARN 로그까지 적용됨.
 *   본 배너는 K-1(사용자 알림 UI) 항목 — backend 안내가 로그로만 남고 사용자가
 *   인지할 수단이 없던 결함을 dashboard 상단 배너로 가시화.
 *
 * 동작:
 *   1. useKisCredentials() query 활용 (기존 endpoint — backend 신규 endpoint 0건).
 *   2. credentials[].accountType==='REAL' + hasKey===false 시 노란 배너 표시.
 *   3. "⚙️ 설정 열기" 버튼 → onOpenSettings() 콜백 (부모가 SettingsModal open).
 *   4. "X" 버튼 → 세션 동안 dismiss (sessionStorage 'banner.kisReal.dismissed').
 *      페이지 새로고침 또는 새 탭에서는 재표시 — 자격증명 등록 전까지 유지.
 *
 * 표시 조건 graceful:
 *   - isLoading: 표시 안 함 (페이지 깜빡임 방지)
 *   - isError(예: 401·온보딩 status 미완): 표시 안 함 (이중 배너 회피)
 *   - 데이터 정상 + REAL hasKey=true: 표시 안 함
 *   - 데이터 정상 + REAL row 미존재: 표시 안 함 (자격증명 0건은 OnboardingGuard가 /setup으로 라우팅)
 *   - 데이터 정상 + REAL row 존재 + hasKey=false: 표시
 *
 * 보안 룰 (메모리 feedback_secret_handling.md 정합):
 *   - 평문 키 표시 0 (useKisCredentials는 mask만 반환 — ADR 0074 §2-2-2)
 *
 * mock 합성 0 (메모리 feedback_no_mock_data.md 정합):
 *   - 데이터 부재 시 배너 표시 안 함 (false 경고 회피)
 */
import { useEffect, useState } from 'react'
import { useKisCredentials } from '../../api/queries/useKisCredentials'

const DISMISS_KEY = 'banner.kisReal.dismissed'

interface KisRealCredentialBannerProps {
  /** 설정 모달 open 콜백 (부모 AppHeader가 KIS 섹션 강조 가능) */
  onOpenSettings: () => void
}

function GearIcon() {
  return (
    <svg
      className="h-4 w-4 shrink-0"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
      />
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  )
}

function WarningIcon() {
  return (
    <svg
      className="h-4 w-4 shrink-0"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126ZM12 15.75h.007v.008H12v-.008Z"
      />
    </svg>
  )
}

function XIcon() {
  return (
    <svg
      className="h-4 w-4 shrink-0"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth={2}
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18 18 6M6 6l12 12" />
    </svg>
  )
}

export function KisRealCredentialBanner({ onOpenSettings }: KisRealCredentialBannerProps) {
  const { data, isLoading, isError } = useKisCredentials()

  // 세션 dismiss 상태 — sessionStorage 기반(새 탭·새로고침 시 reset)
  const [dismissed, setDismissed] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false
    try {
      return sessionStorage.getItem(DISMISS_KEY) === '1'
    } catch {
      return false
    }
  })

  // 다른 탭에서 dismiss 시 sync (sessionStorage는 탭 단위라 strict 동기화 불필요)
  useEffect(() => {
    if (dismissed && typeof window !== 'undefined') {
      try {
        sessionStorage.setItem(DISMISS_KEY, '1')
      } catch {
        // private 모드 등 sessionStorage 비활성 — graceful 무시
      }
    }
  }, [dismissed])

  // graceful: 로딩·에러·dismiss 시 표시 안 함
  if (isLoading || isError || dismissed) return null

  // REAL 자격증명 row 검사
  const realCred = data?.credentials.find((c) => c.accountType === 'REAL')
  // row 미존재 → /setup 라우팅 대상이므로 본 배너는 표시 안 함 (이중 안내 회피)
  // row 존재 + hasKey=true → 정상
  if (!realCred || realCred.hasKey) return null

  // REAL row 존재 + hasKey=false → 배너 표시
  return (
    <div
      role="status"
      aria-live="polite"
      className="border-b border-yellow-800/50 bg-yellow-900/30"
    >
      <div className="mx-auto flex max-w-7xl items-center gap-3 px-4 py-2">
        <span className="text-yellow-400">
          <WarningIcon />
        </span>
        <p className="flex-1 text-sm text-yellow-200">
          <span className="font-medium">KIS 실전 자격증명이 등록되지 않아 시세 WebSocket이 작동하지 않습니다.</span>
          <span className="ml-2 text-yellow-300/80">설정 → KIS 키 관리에서 실전 키를 등록해 주세요.</span>
        </p>
        <button
          type="button"
          onClick={onOpenSettings}
          aria-label="설정 모달 열기 — KIS 키 등록"
          className="flex items-center gap-1.5 rounded-md border border-yellow-700/60 bg-yellow-800/30 px-3 py-1 text-xs font-medium text-yellow-100 transition-colors hover:bg-yellow-700/40 focus:outline-none focus:ring-2 focus:ring-yellow-400"
        >
          <GearIcon />
          설정 열기
        </button>
        <button
          type="button"
          onClick={() => setDismissed(true)}
          aria-label="배너 닫기 (이번 세션 동안)"
          className="rounded-md p-1 text-yellow-400/70 transition-colors hover:bg-yellow-800/40 hover:text-yellow-200 focus:outline-none focus:ring-2 focus:ring-yellow-400"
        >
          <XIcon />
        </button>
      </div>
    </div>
  )
}
