/**
 * 앱 상단 네비게이션 헤더
 *
 * spec §3-2 E 정합:
 * - "magicJar" 로고 + "대시보드" + 시장/전략/리스크 메뉴
 * - <NavLink> — 활성 메뉴 highlight (aria-current 기반)
 * - SetupWizard(/setup)에서는 미표시 (App.tsx에서 제어)
 * - T-SIG Phase 3 (ADR 0066): "전략 목록" 메뉴 추가
 * - T-RISK Phase 3 (ADR 0067): "리스크 설정"·"위반 이력" 메뉴 추가
 * - T-O Phase 3 (ADR 0068): "주문 내역" 메뉴 추가
 * - T-J Phase 3 (ADR 0072): "매매일지" 메뉴를 우측 단일 메뉴로 배치
 * - T-PERF Phase 3 (ADR 0072): "성과"는 대시보드 내부 전환 대상으로 유지
 * - T-SIM Phase 3 (ADR 0107): "시나리오 리플레이"를 전략 메뉴 하위로 배치
 * - ADR 0069 §6: ⚙️ SettingsModal 진입점 추가
 * - backlog §58 / ADR 0077 K-1: KisRealCredentialBanner — REAL 미등록 시 안내
 * - P2-2 frontend: RuntimeFlagsIndicator — 로고 옆 dot LED + 모드 라벨 (MOCK/REAL/Stop)
 * - P2 Task 8 (system_event_log): NotificationCenter 벨 + 드롭다운 + 전역 STOMP 구독
 *   (useSystemEventStompSubscription — /topic/system.event.user.{userId} + broadcast)
 */
import { useEffect, useMemo, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { SettingsIcon } from '../icons/SettingsIcon'
import { SettingsModal } from '../settings/SettingsModal'
import { KisRealCredentialBanner } from './KisRealCredentialBanner'
import { RuntimeFlagsIndicator } from './RuntimeFlagsIndicator'
import { NotificationCenter } from '../notification/NotificationCenter'
import { WsConnectionIndicator } from './WsConnectionIndicator'
import { KisWsHealthIndicator } from './KisWsHealthIndicator'
import { useSystemEventStompSubscription } from '../../api/hooks/useSystemEventStompSubscription'
import { useOnboardingStore } from '../../stores/onboarding'

interface NavItem {
  to: string
  label: string
  end?: boolean
}

interface NavGroup {
  label: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    label: '시장',
    items: [
      { to: '/instruments', label: '종목검색' },
      { to: '/positions', label: '포지션' },
      { to: '/orders', label: '주문 내역' },
      { to: '/market/trend', label: '시장 추세' },
      { to: '/market/calendar', label: '시장 캘린더' },
    ],
  },
  {
    // 검증 퍼널 순서 정렬 (2026-06-10 strategy-mock-paper-flow 보고서 발전점 #5):
    // 발굴 → 리서치 → 빌더 → 백테스트 → 모의 → 스코어카드 → 목록 → 리플레이
    label: '전략',
    items: [
      { to: '/strategy/daytrade-discover', label: '단타 발굴' },
      { to: '/strategy/research', label: '전략 리서치' },
      { to: '/strategy/builder', label: '전략 빌더' },
      { to: '/strategy/backtest', label: '백테스트' },
      { to: '/strategy/paper', label: '모의 트레이딩' },
      { to: '/strategy/scorecard', label: '발굴 스코어카드' },
      { to: '/strategy/list', label: '전략 목록' },
      { to: '/strategy/scenario-replay', label: '시나리오 리플레이' },
    ],
  },
  {
    label: '리스크',
    items: [
      { to: '/risk-config', label: '리스크 설정' },
      { to: '/risk-events', label: '위반 이력' },
    ],
  },
  {
    // 운영 그룹 신설 — 최고 관리자 장애 처리용 가시화 화면 모음
    label: '운영',
    items: [
      { to: '/admin/realtime', label: '실시간 상태' },
      { to: '/system', label: '시스템 이벤트' },
    ],
  },
]

function isActivePath(pathname: string, item: NavItem): boolean {
  if (item.end) return pathname === item.to
  if (item.to === '/instruments' && pathname.startsWith('/symbol/')) return true
  return pathname === item.to || pathname.startsWith(`${item.to}/`)
}

function NavGroupMenu({ group }: { group: NavGroup }) {
  const [open, setOpen] = useState(false)
  const closeTimerRef = useRef<number | null>(null)
  const location = useLocation()
  const navigate = useNavigate()
  const active = group.items.some((item) => isActivePath(location.pathname, item))

  const cancelClose = () => {
    if (closeTimerRef.current !== null) {
      window.clearTimeout(closeTimerRef.current)
      closeTimerRef.current = null
    }
  }

  const openMenu = () => {
    cancelClose()
    setOpen(true)
  }

  const closeMenu = () => {
    cancelClose()
    setOpen(false)
  }

  const scheduleClose = () => {
    cancelClose()
    closeTimerRef.current = window.setTimeout(() => {
      setOpen(false)
      closeTimerRef.current = null
    }, 180)
  }

  const navigateToFirstItem = () => {
    const firstItem = group.items[0]
    if (!firstItem) return
    closeMenu()
    navigate(firstItem.to)
  }

  useEffect(() => () => cancelClose(), [])

  return (
    <div
      className="relative"
      onMouseEnter={openMenu}
      onMouseLeave={scheduleClose}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
          closeMenu()
        }
      }}
    >
      <button
        type="button"
        onClick={navigateToFirstItem}
        onFocus={openMenu}
        aria-haspopup="true"
        aria-expanded={open}
        className={[
          'flex h-9 items-center gap-1 rounded-lg px-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
          active
            ? 'bg-gray-800 text-white'
            : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
        ].join(' ')}
      >
        {group.label}
        <span className="text-[10px] text-gray-500" aria-hidden>
          ▾
        </span>
      </button>

      {open && (
        <div className="absolute left-0 top-full z-50 min-w-44 pt-2" onMouseEnter={openMenu}>
          <div className="rounded-lg border border-gray-800 bg-gray-950 p-1 shadow-xl">
            {group.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                onClick={closeMenu}
                className={({ isActive }) =>
                  [
                    'block rounded-md px-3 py-2 text-sm transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400',
                    isActive
                      ? 'bg-blue-600 text-white'
                      : 'text-gray-400 hover:bg-gray-800 hover:text-gray-200',
                  ].join(' ')
                }
              >
                {item.label}
              </NavLink>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function TopNavLink({ to, label }: NavItem) {
  return (
    <NavLink
      to={to}
      end={to === '/'}
      className={({ isActive }) =>
        [
          'flex h-9 shrink-0 items-center rounded-lg px-3 text-sm font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950',
          isActive
            ? 'bg-gray-800 text-white'
            : 'text-gray-400 hover:bg-gray-800/60 hover:text-gray-200',
        ].join(' ')
      }
    >
      {label}
    </NavLink>
  )
}

export function AppHeader() {
  // ADR 0069 §6 — SettingsModal 열림 상태 (로컬 hook)
  const [settingsOpen, setSettingsOpen] = useState(false)

  // P2 Task 8 — userId 획득 (number | null) → STOMP topic/REST API 는 string 사용 → 변환
  const registeredUserId = useOnboardingStore((s) => s.registeredUserId)
  const userIdStr = useMemo<string | null>(
    () => (registeredUserId !== null ? String(registeredUserId) : null),
    [registeredUserId],
  )

  // P2 Task 8 — 전역 STOMP 구독 (/topic/system.event.user.{userId} + broadcast)
  // 메시지 수신 시 query invalidate + CRITICAL toast (hook 내부 처리)
  useSystemEventStompSubscription(userIdStr)

  return (
    <>
    <header className="sticky top-0 z-50 border-b border-gray-800 bg-gray-950/95 backdrop-blur">
      {/* KIS 실전 자격증명 미등록 경고 배너 — backlog §58 / ADR 0077 K-1 */}
      <KisRealCredentialBanner onOpenSettings={() => setSettingsOpen(true)} />

      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-3 px-4 py-3 lg:flex-nowrap lg:justify-between">
        {/* 로고 + RuntimeFlags indicator (P2-2 frontend) */}
        <div className="flex shrink-0 items-center gap-3">
          <NavLink
            to="/"
            className="text-xl font-bold tracking-tight text-white hover:text-gray-200"
          >
            magic<span className="text-blue-400">Jar</span>
          </NavLink>
          <RuntimeFlagsIndicator userId={registeredUserId} />
        </div>

        {/* 네비게이션 메뉴 */}
        <nav
          className="order-3 flex w-full items-center gap-1 overflow-x-auto pb-1 lg:order-none lg:w-auto lg:overflow-visible lg:pb-0"
          aria-label="메인 메뉴"
        >
          <TopNavLink to="/" label="대시보드" end />

          {NAV_GROUPS.map((group) => (
            <NavGroupMenu key={group.label} group={group} />
          ))}

          <TopNavLink to="/journal" label="매매일지" />

          {/* WS 접속 상태 indicator — 사용자 결재 2026-05-20 (헤더 영구 표시) */}
          {/* 사용자 dogfooding 2026-05-20: STOMP + KIS WS 2 indicator 분리 — 각각 상태 + 각각 toast */}
          <div className="ml-2 flex items-center gap-2">
            <WsConnectionIndicator />
            <KisWsHealthIndicator />
          </div>

          {/* P2 Task 8: 시스템 이벤트 알림센터 — 벨 + unread badge + 드롭다운 + /system 링크 */}
          <div className="ml-2">
            <NotificationCenter userId={userIdStr} />
          </div>

          {/* SettingsModal 진입점 — ADR 0069 §6 */}
          <button
            type="button"
            onClick={() => setSettingsOpen(true)}
            aria-label="설정"
            className="ml-1 rounded-lg px-2.5 py-1.5 text-gray-400 hover:bg-gray-800/60 hover:text-gray-200 focus:outline-none focus:ring-2 focus:ring-blue-400 focus:ring-offset-1 focus:ring-offset-gray-950"
          >
            <SettingsIcon className="h-4 w-4" />
          </button>
        </nav>
      </div>
    </header>

    {/* SettingsModal — conditional render (unmount 시 form state 자연 reset) */}
    {settingsOpen && (
      <SettingsModal onClose={() => setSettingsOpen(false)} />
    )}
    </>
  )
}
