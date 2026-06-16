/**
 * 실시간 구독 상태 관리자 화면 — 컴포넌트 단위 테스트
 *
 * 화면: /admin/realtime (RealtimeStatusPage + 4 하위 컴포넌트)
 * 계약: GET /api/admin/realtime-status (RealtimeStatus — 필드명 LOCK)
 *
 * 검증 초점 (장애 처리용 가시화의 핵심 분기):
 *  1. Banner       — 정상/비정상 분기 + 비정상 시 한국어 장애 힌트 노출
 *  2. SlotGauge    — 사용률 임계(70%/90%) 색·힌트 + trId 분해 + 빈 상태
 *  3. SubTable     — owner 색 배지 + 빈 상태 정직 표기 (mock 0)
 *  4. CandlePanel  — 180초 임계 경고 + 15:20 마감 동시호가 정상 힌트 + 빈 상태
 *
 * 순수 props 컴포넌트라 MSW 없이 직접 주입으로 분기만 검증한다.
 */
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { ReactElement } from 'react'
import { describe, expect, it } from 'vitest'
import type {
  RealtimeCandleFreshness,
  RealtimeExecutionNotice,
  RealtimeSlots,
  RealtimeSubscription,
  RealtimeWsStatus,
} from '../../api/adminRealtime'
import { RealtimeStatusBanner } from '../../components/admin/RealtimeStatusBanner'
import { RealtimeSlotGauge } from '../../components/admin/RealtimeSlotGauge'
import { RealtimeSubscriptionTable } from '../../components/admin/RealtimeSubscriptionTable'
import { RealtimeCandleFreshnessPanel } from '../../components/admin/RealtimeCandleFreshnessPanel'

/** Link 를 쓰는 컴포넌트용 router 래퍼 render */
function renderWithRouter(ui: ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>)
}

// ──────────────────────────────────────────────
// fixture
// ──────────────────────────────────────────────

const wsConnected: RealtimeWsStatus = {
  connected: true,
  sessionCid: 'sess-abc',
  reconnects24h: 0,
  lastConnectedAt: '2026-06-12T09:00:00+09:00',
}

const noticeOk: RealtimeExecutionNotice = {
  subscribed: true,
  mode: 'MOCK',
  userIdMasked: 'sp****',
}

// ──────────────────────────────────────────────
// ① Banner
// ──────────────────────────────────────────────

describe('RealtimeStatusBanner', () => {
  it('모두 정상이면 비정상 강조가 없다', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={noticeOk}
      />,
    )
    const banner = screen.getByTestId('realtime-banner')
    expect(banner.getAttribute('data-abnormal')).toBe('false')
    // 3 카드 모두 정상
    expect(screen.getByTestId('status-card-WS 연결').getAttribute('data-ok')).toBe('true')
    expect(screen.getByTestId('status-card-상태 신선도').getAttribute('data-ok')).toBe('true')
    expect(screen.getByTestId('status-card-체결 통보').getAttribute('data-ok')).toBe('true')
  })

  it('WS 끊김이면 비정상 강조 + 한국어 장애 힌트가 뜬다', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={{ ...wsConnected, connected: false }}
        executionNotice={noticeOk}
      />,
    )
    expect(screen.getByTestId('realtime-banner').getAttribute('data-abnormal')).toBe('true')
    expect(screen.getByTestId('status-card-WS 연결').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/KIS WS 미연결/)).toBeInTheDocument()
  })

  it('stale=true 면 상태 신선도가 비정상으로 표시된다', () => {
    render(
      <RealtimeStatusBanner
        stale
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={noticeOk}
      />,
    )
    expect(screen.getByTestId('status-card-상태 신선도').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/consumer가 상태를 못 쓰고/)).toBeInTheDocument()
  })

  it('체결통보 미구독이면 비정상 + 힌트', () => {
    render(
      <RealtimeStatusBanner
        stale={false}
        updatedAt="2026-06-12T15:00:00+09:00"
        ws={wsConnected}
        executionNotice={{ subscribed: false, mode: null, userIdMasked: null }}
      />,
    )
    expect(screen.getByTestId('status-card-체결 통보').getAttribute('data-ok')).toBe('false')
    expect(screen.getByText(/체결 통보 구독 끊김/)).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ② SlotGauge
// ──────────────────────────────────────────────

describe('RealtimeSlotGauge', () => {
  it('used/max 와 사용률 + trId 라벨·코드 병기를 표시한다', () => {
    const slots: RealtimeSlots = { used: 12, max: 40, byTrId: { H0UNCNT0: 8, H0UNASP0: 4 } }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/40/)).toBeInTheDocument()
    expect(screen.getByText(/30%/)).toBeInTheDocument()
    // trId 코드 + 한국어 라벨 병기
    expect(screen.getByText('H0UNCNT0')).toBeInTheDocument()
    expect(screen.getByText('H0UNASP0')).toBeInTheDocument()
    expect(screen.getByText('체결가(통합)')).toBeInTheDocument()
    expect(screen.getByText('호가(통합)')).toBeInTheDocument()
    // 채널 카운트 병기
    expect(screen.getByText('8채널')).toBeInTheDocument()
  })

  it('trId 행 클릭 시 점유 종목 목록이 펼쳐진다 (drill-down)', () => {
    const slots: RealtimeSlots = {
      used: 2,
      max: 40,
      byTrId: { H0STCNT0: 2 },
      channelsByTrId: {
        H0STCNT0: [
          { code: '005930', codeName: '삼성전자' },
          { code: '000660', codeName: null },
        ],
      },
    }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    // 펼치기 전엔 종목명 미노출
    expect(screen.queryByText('삼성전자')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /체결가/ }))
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    // codeName null 이면 코드만 (링크)
    expect(screen.getByRole('link', { name: '000660' })).toBeInTheDocument()
  })

  it('구버전 스냅샷(channelsByTrId 없음)이면 상세 정보 없음을 표기한다', () => {
    const slots: RealtimeSlots = { used: 8, max: 40, byTrId: { H0STCNT0: 8 } }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    fireEvent.click(screen.getByRole('button', { name: /체결가/ }))
    expect(screen.getByText(/상세 정보 없음 \(구버전 스냅샷/)).toBeInTheDocument()
  })

  it('사용률 90% 이상이면 고갈 임박 힌트가 뜬다', () => {
    const slots: RealtimeSlots = { used: 38, max: 40, byTrId: {} }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/슬롯 고갈 임박/)).toBeInTheDocument()
  })

  it('사용률 70~90% 면 사용률 높음 힌트가 뜬다', () => {
    const slots: RealtimeSlots = { used: 32, max: 40, byTrId: {} }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/슬롯 사용률 높음/)).toBeInTheDocument()
  })

  it('trId 점유가 없으면 정직하게 안내한다', () => {
    const slots: RealtimeSlots = { used: 0, max: 40, byTrId: {} }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/점유 중인 trId가 없습니다/)).toBeInTheDocument()
  })

  it('max=0 이어도 0 나눗셈 없이 0%로 처리한다', () => {
    const slots: RealtimeSlots = { used: 0, max: 0, byTrId: {} }
    renderWithRouter(<RealtimeSlotGauge slots={slots} />)
    expect(screen.getByText(/0%/)).toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ③ SubscriptionTable
// ──────────────────────────────────────────────

describe('RealtimeSubscriptionTable', () => {
  const subs: RealtimeSubscription[] = [
    {
      trId: 'H0STCNT0',
      code: '005930',
      codeName: '삼성전자',
      owners: ['strategy:86', 'detail:74683223'],
      since: '2026-06-12T09:01:00+09:00',
    },
  ]

  it('구독 항목과 인간화된 owner 배지·trId 라벨을 렌더링한다', () => {
    renderWithRouter(<RealtimeSubscriptionTable subscriptions={subs} />)
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
    expect(screen.getByText('005930')).toBeInTheDocument()
    // trId 라벨 병기
    expect(screen.getByText('체결가')).toBeInTheDocument()
    // owner 배지 — 인간화 라벨
    expect(screen.getByText('전략 #86')).toBeInTheDocument()
    // detail owner 배지는 id 없이 '종목 상세'로 단순화됨 (bcc12884 — id는 hover title에만)
    expect(screen.getAllByText('종목 상세').length).toBeGreaterThan(0)
  })

  it('legacy 이중 prefix(strategy:strategy:86)를 collapse 해 표기한다', () => {
    renderWithRouter(
      <RealtimeSubscriptionTable
        subscriptions={[{ ...subs[0], owners: ['strategy:strategy:86'] }]}
      />,
    )
    // 중복 prefix 가 접혀 "전략 #86" 으로 (배지 + 범례 중복 가능 → getAllByText)
    expect(screen.getAllByText('전략 #86').length).toBeGreaterThan(0)
  })

  it('종목명을 클릭하면 /symbol/:code 로 가는 링크다', () => {
    renderWithRouter(<RealtimeSubscriptionTable subscriptions={subs} />)
    const link = screen.getByRole('link', { name: /삼성전자/ })
    expect(link).toHaveAttribute('href', '/symbol/005930')
  })

  it('빈 목록이면 정직하게 표기한다 (mock 0)', () => {
    renderWithRouter(<RealtimeSubscriptionTable subscriptions={[]} />)
    expect(screen.getByText(/구독 중인 종목이 없습니다/)).toBeInTheDocument()
  })

  it('owner 가 없는 구독은 (owner 없음) 으로 표기한다', () => {
    renderWithRouter(
      <RealtimeSubscriptionTable
        subscriptions={[{ ...subs[0], owners: [] }]}
      />,
    )
    expect(screen.getByText(/owner 없음/)).toBeInTheDocument()
  })

  it('장운영 VI 구독은 한글 trId·owner 라벨과 미기록 시각으로 표시한다', () => {
    renderWithRouter(
      <RealtimeSubscriptionTable
        subscriptions={[
          {
            trId: 'H0UNMKO0',
            code: '005930',
            codeName: '삼성전자',
            owners: ['vi:held'],
            since: null,
          },
        ]}
      />,
    )
    expect(screen.getByText('장운영/VI(통합)')).toBeInTheDocument()
    expect(screen.getByText('H0UNMKO0')).toBeInTheDocument()
    expect(screen.getAllByText('VI 보유 감시').length).toBeGreaterThan(0)
    expect(screen.getByText('구독 시각 미기록')).toBeInTheDocument()
    expect(screen.queryByText(/1970/)).not.toBeInTheDocument()
  })
})

// ──────────────────────────────────────────────
// ④ CandleFreshnessPanel
// ──────────────────────────────────────────────

describe('RealtimeCandleFreshnessPanel', () => {
  it('판독 가이드는 (!) 아이콘 tooltip 으로 상시 노출된다 (정체 유무 무관)', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', codeName: '삼성전자', interval: '1m', ageSeconds: 5 },
    ]
    renderWithRouter(<RealtimeCandleFreshnessPanel items={items} />)
    // 큰 amber 박스(<p>) 대신 (!) 아이콘 버튼 + role=tooltip 가이드
    const guide = screen.getByRole('tooltip')
    expect(guide).toHaveTextContent(/시세 유입 정지 의심/)
    expect(guide).toHaveTextContent(/15:20 이후/)
    // 접근성: 아이콘 버튼에 aria-label + 키보드 focus 가능
    expect(
      screen.getByRole('button', { name: /캔들 신선도 판독 가이드/ }),
    ).toBeInTheDocument()
  })

  it('180초 초과 항목이 있으면 정체 건수 배지를 노출한다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', codeName: '삼성전자', interval: '1m', ageSeconds: 240 },
      { code: '000660', codeName: 'SK하이닉스', interval: '1m', ageSeconds: 12 },
    ]
    renderWithRouter(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.getByText(/정체 1건/)).toBeInTheDocument()
    // 종목명 표시
    expect(screen.getByText('삼성전자')).toBeInTheDocument()
  })

  it('codeName 이 null 이면 코드만 표시한다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', codeName: null, interval: '1m', ageSeconds: 5 },
    ]
    renderWithRouter(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.getByRole('link', { name: /005930/ })).toHaveAttribute('href', '/symbol/005930')
  })

  it('모두 신선하면 정체 배지가 없다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: '005930', codeName: '삼성전자', interval: '1m', ageSeconds: 5 },
    ]
    renderWithRouter(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.queryByText(/정체/)).not.toBeInTheDocument()
    expect(screen.getByText(/5초 전/)).toBeInTheDocument()
  })

  it('빈 목록이면 정직하게 표기한다 (mock 0)', () => {
    renderWithRouter(<RealtimeCandleFreshnessPanel items={[]} />)
    expect(screen.getByText(/캔들 신선도 데이터가 없습니다/)).toBeInTheDocument()
  })

  it('경과 시간을 시/분/초 한국어로 포맷한다', () => {
    const items: RealtimeCandleFreshness[] = [
      { code: 'A', codeName: '초', interval: '1m', ageSeconds: 39 },
      { code: 'B', codeName: '분초', interval: '1m', ageSeconds: 200 },
      { code: 'C', codeName: '시분초', interval: '1m', ageSeconds: 58599 },
      { code: 'D', codeName: '정시', interval: '1m', ageSeconds: 3600 },
      { code: 'E', codeName: '일', interval: '1m', ageSeconds: 194400 },
    ]
    renderWithRouter(<RealtimeCandleFreshnessPanel items={items} />)
    expect(screen.getByText('39초 전')).toBeInTheDocument()
    expect(screen.getByText('3분 20초 전')).toBeInTheDocument()
    expect(screen.getByText('16시간 16분 39초 전')).toBeInTheDocument()
    expect(screen.getByText('1시간 전')).toBeInTheDocument()
    expect(screen.getByText('2일 6시간 전')).toBeInTheDocument()
  })
})
