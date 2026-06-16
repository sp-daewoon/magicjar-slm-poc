/**
 * 라우터 최상위 컴포넌트 + 온보딩 라우팅 가드
 *
 * spec §5-2 (Step 1 라우팅 가드) 정합:
 * - 진입 시 GET /api/onboarding/status 조회
 * - hasUserAccount=false + path !== '/setup' → Navigate to="/setup"
 * - hasUserAccount=true + path === '/setup' → Navigate to="/" (이미 완료된 사용자 차단)
 * - isLoading → 스플래시 표시
 *
 * 라우트 (PR-5b 구조 유지 + PR-6 확장 + PR-7 추가 + PR-10 추가 + PR-RISK Phase 3 + T-O Phase 3 + T-J·T-PERF Phase 3 + T-ACCT Phase 4):
 *   /                 -> 대시보드 (T-ACCT Phase 4 — AccountSummary + Holdings + Balance, ADR 0081)
 *   /watchlist        -> /instruments?watchlist=1 리다이렉트 (기존 URL 호환)
 *   /instruments      -> 전체 종목검색 + 관심종목 필터 + 섹터 그룹
 *   /symbol/:code     -> 종목 상세 (T-S1 — ADR 0040 §2-2 진입점)
 *   /strategy/backtest -> 백테스트 (T-BT R3 — ADR 0061 §4 D-1)
 *   /strategy/paper   -> 모의(페이퍼) 트레이딩 대시보드 (ADR 0122 R3 — 실거래 아님)
 *   /strategy/scorecard -> 발굴 스코어카드 (V77 forward-test — read-only)
 *   /strategy/research -> 전략 리서치 (QA 전용 후보 생성)
 *   /strategy/scenario-replay -> 시나리오 리플레이 (T-SIM Phase 3 — ADR 0107)
 *   /strategy/list    -> 전략 목록 + 시그널 내역 (T-SIG Phase 3 — ADR 0066)
 *   /risk-config      -> 리스크 설정 (T-RISK Phase 3 — ADR 0067)
 *   /risk-events      -> 리스크 위반 이력 (T-RISK Phase 3 — ADR 0067)
 *   /positions        -> 보유 포지션 (T-RISK Phase 3 — ADR 0067)
 *   /orders           -> 주문 내역 (T-O Phase 3 — ADR 0068)
 *   /orders/:id       -> 주문 상세 (T-O Phase 3 — ADR 0068)
 *   /journal          -> 매매일지 시계열 (T-J Phase 3 — ADR 0072)
 *   /performance      -> /?view=performance 리다이렉트 (대시보드 성과 탭)
 *   /system           -> 시스템 이벤트 운영 대시보드 (P2 Task 7 — ADR 0084 P0 system_event_log)
 *   /market/trend     -> 시장 추세 분석 (W4 T-MARKET R2 — ADR 0100·0101)
 *   /market/calendar  -> 시장 캘린더 (KRX 휴장일 + 사용자 시장 이벤트)
 *   /setup            -> 셋업 마법사 7단계 (T-ONBOARD)
 *   *                 -> / 리다이렉트
 *
 * AppHeader:
 *   /setup 경로에서는 미표시 (셋업 흐름 UI 단순화)
 *   그 외 경로는 AppHeader로 감싸기
 */
import type { ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useOnboardingStatus } from './api/onboarding'
import { AppHeader } from './components/layout/AppHeader'
import { DashboardPage } from './pages/Dashboard/DashboardPage'
import { SetupWizard } from './pages/SetupWizard'
import { SymbolDetailPage } from './pages/Symbol/SymbolDetailPage'
import { InstrumentUniversePage } from './pages/Instruments/InstrumentUniversePage'
import { StrategyBuilderPage } from './pages/Strategy/StrategyBuilderPage'
import { BacktestPage } from './pages/Strategy/BacktestPage'
import { StrategyResearchPage } from './pages/Strategy/StrategyResearchPage'
import { DaytradeDiscoverPage } from './pages/Strategy/DaytradeDiscoverPage'
import { PaperDashboardPage } from './pages/Strategy/PaperDashboardPage'
import { DiscoveryScorecardPage } from './pages/Strategy/DiscoveryScorecardPage'
import { StrategyListPage } from './pages/Strategy/StrategyListPage'
import { ScenarioReplayPage } from './pages/QA/ScenarioReplayPage'
import { RiskConfigPage } from './pages/Risk/RiskConfigPage'
import { RiskEventsPage } from './pages/Risk/RiskEventsPage'
import { PositionsPage } from './pages/Risk/PositionsPage'
import { OrdersPage } from './pages/orders/OrdersPage'
import { OrderDetailPage } from './pages/orders/OrderDetailPage'
import { NewOrderPage } from './pages/orders/NewOrderPage'
import { TradingJournalPage } from './pages/TradingJournalPage'
import { PerformanceDashboardPage } from './pages/PerformanceDashboardPage'
import { SystemEventPage } from './pages/SystemEvent/SystemEventPage'
import { RealtimeStatusPage } from './pages/Admin/RealtimeStatusPage'
import { MarketCalendarPage } from './features/market/pages/MarketCalendarPage'
import { MarketTrendPage } from './features/market/pages/MarketTrendPage'
import { useStompReconnect } from './api/hooks/useStompReconnect'
import { useOnboardingStore } from './stores/onboarding'

/** 온보딩 라우팅 가드 — status 기반 자동 분기 */
function OnboardingGuard({ children }: { children: ReactNode }) {
  const location = useLocation()
  const { data, isLoading, isError } = useOnboardingStatus()

  // 로딩 중 스플래시
  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-950">
        <div className="flex flex-col items-center gap-4">
          <div className="h-10 w-10 animate-spin rounded-full border-4 border-gray-800 border-t-blue-500" />
          <p className="text-sm text-gray-500">로딩 중...</p>
        </div>
      </div>
    )
  }

  // status 조회 실패 시 — /setup은 항상 접근 허용, 나머지는 그대로
  if (isError) {
    return <>{children}</>
  }

  const isSetupPath = location.pathname === '/setup'

  // 셋업 미완 + /setup 이외 경로 → /setup 이동
  if (data && !data.hasUserAccount && !isSetupPath) {
    return <Navigate to="/setup" replace />
  }

  // 셋업 완료 + /setup 진입 → / 이동 (중복 진입 차단)
  if (data && data.hasUserAccount && isSetupPath) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}

/** /setup 경로 제외 시 AppHeader 표시 레이아웃 */
function AppLayout({ children }: { children: ReactNode }) {
  const location = useLocation()
  const isSetupPath = location.pathname === '/setup'

  // P1-3 frontend — STOMP 재연결 시 backfill (userId 있을 때만, /setup 외부에서)
  const userId = useOnboardingStore((s) => s.registeredUserId)
  useStompReconnect({ userId, enabled: !isSetupPath })

  if (isSetupPath) {
    return <>{children}</>
  }

  return (
    <div className="min-h-screen bg-gray-950">
      <AppHeader />
      <main>{children}</main>
    </div>
  )
}

export function App() {
  return (
    <OnboardingGuard>
      <AppLayout>
        <Routes>
          {/* T-ACCT Phase 4: 대시보드 격상 — AccountSummary + HoldingsTable + BalanceCard (ADR 0081) */}
          <Route path="/" element={<DashboardPage />} />
          <Route path="/watchlist" element={<Navigate to="/instruments?watchlist=1" replace />} />
          <Route path="/instruments" element={<InstrumentUniversePage />} />
          {/* T-S1: 종목 상세 — ADR 0040 §2-2 진입점 */}
          <Route path="/symbol/:code" element={<SymbolDetailPage />} />
          {/* T-SB: 전략 빌더 — ADR 0040 §1 진입점 */}
          <Route path="/strategy/builder" element={<StrategyBuilderPage />} />
          {/* T-BT R3: 백테스트 — ADR 0061 §4 D-1 결정 */}
          <Route path="/strategy/backtest" element={<BacktestPage />} />
          {/* QA: candle 기반 전략 후보 리서치 — 저장/주문 path 미사용 */}
          <Route path="/strategy/research" element={<StrategyResearchPage />} />
          {/* R2 단타 발굴: 전략 없이 빌트인 프리셋으로 오늘의 후보 TOP N — ADR 0117 */}
          <Route path="/strategy/daytrade-discover" element={<DaytradeDiscoverPage />} />
          {/* ADR 0122 R3: 발굴 → MOCK 페이퍼 트레이딩 대시보드 (모의 — 실거래 아님) */}
          <Route path="/strategy/paper" element={<PaperDashboardPage />} />
          {/* 발굴 스코어카드: V77 forward-test 데이터의 유일한 화면 (read-only — 매매 결정권 0) */}
          <Route path="/strategy/scorecard" element={<DiscoveryScorecardPage />} />
          {/* T-SIG Phase 3: 전략 목록 + 시그널 내역 — ADR 0066 */}
          <Route path="/strategy/list" element={<StrategyListPage />} />
          {/* T-SIM Phase 3: 실제 주문과 분리된 시나리오 리플레이 — ADR 0107 */}
          <Route path="/strategy/scenario-replay" element={<ScenarioReplayPage />} />
          <Route path="/qa/scenario-replay" element={<Navigate to="/strategy/scenario-replay" replace />} />
          {/* T-RISK Phase 3: 리스크 설정·위반 이력·포지션 — ADR 0067 */}
          <Route path="/risk-config" element={<RiskConfigPage />} />
          <Route path="/risk-events" element={<RiskEventsPage />} />
          <Route path="/positions" element={<PositionsPage />} />
          {/* T-O Phase 3: 주문 내역·상세 — ADR 0068 */}
          <Route path="/orders" element={<OrdersPage />} />
          {/* T-MAN Phase 2: 수동 주문 발행 — ADR 0082 (반드시 /orders/:id 보다 먼저 등록) */}
          <Route path="/orders/new" element={<NewOrderPage />} />
          <Route path="/orders/:id" element={<OrderDetailPage />} />
          {/* T-J Phase 3: 매매일지 — ADR 0072 */}
          <Route path="/journal" element={<TradingJournalPage />} />
          {/* T-PERF Phase 3: 성과는 대시보드 탭으로 통합 — 기존 URL 호환 */}
          <Route path="/performance" element={<PerformanceDashboardPage />} />
          {/* P2 Task 7: 시스템 이벤트 운영 대시보드 — ADR 0084 P0 system_event_log */}
          <Route path="/system" element={<SystemEventPage />} />
          {/* 실시간 구독 상태 관리자 화면 — 최고 관리자 장애 처리용 (GET /api/admin/realtime-status) */}
          <Route path="/admin/realtime" element={<RealtimeStatusPage />} />
          {/* W4 T-MARKET R2: 시장 추세 분석 — ADR 0100·0101 */}
          <Route path="/market/trend" element={<MarketTrendPage />} />
          <Route path="/market/calendar" element={<MarketCalendarPage />} />
          <Route path="/setup" element={<SetupWizard />} />
          {/* 미정의 경로는 / 로 리다이렉트 */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AppLayout>
    </OnboardingGuard>
  )
}
