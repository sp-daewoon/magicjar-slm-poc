/**
 * useStompReconnect 단위 테스트.
 *
 * 검증 초점:
 * - backend StompEventBackfillController 실제 DTO(content/firstTs/lastTs/createdAt)와 정합
 * - system-event 채널은 전용 알림센터가 담당하므로 주문·시그널·리스크 백필 토스트에서 제외
 */
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useStompReconnect } from '../useStompReconnect'

const mocks = vi.hoisted(() => ({
  getUnreadCount: vi.fn(),
  list: vi.fn(),
  showToast: vi.fn(),
  client: {
    connected: true,
    onConnect: undefined as ((frame: unknown) => void) | undefined,
  },
}))

vi.mock('../../stompBackfill', () => ({
  stompBackfillApi: {
    getUnreadCount: mocks.getUnreadCount,
    list: mocks.list,
  },
}))

vi.mock('../../../lib/stomp', () => ({
  getStompClient: () => mocks.client,
}))

vi.mock('../../../components/ui/ToastProvider', () => ({
  useOptionalToast: () => ({ showToast: mocks.showToast }),
}))

const userId = 74683223
const since = '2026-05-19T00:00:00.000Z'

beforeEach(() => {
  mocks.getUnreadCount.mockReset()
  mocks.list.mockReset()
  mocks.showToast.mockReset()
  mocks.client.connected = true
  mocks.client.onConnect = undefined
  localStorage.clear()
  localStorage.setItem(`stomp:lastSeenAt:${userId}`, since)
})

describe('useStompReconnect', () => {
  it('order/signal/risk 채널만 unread count 조회하고 system-event 는 제외한다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) =>
      Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel }),
    )

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.getUnreadCount).toHaveBeenCalledTimes(3)
    })

    expect(mocks.getUnreadCount.mock.calls.map((call) => call[2])).toEqual([
      'order',
      'signal',
      'risk',
    ])
    expect(mocks.list).not.toHaveBeenCalled()
    expect(mocks.showToast).not.toHaveBeenCalled()
  })

  it('backend content 응답을 payloadJson 기준으로 파싱하고 lastTs 로 anchor 를 갱신한다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 108,
          firstTs: '2026-05-20T00:00:00Z',
          lastTs: '2026-05-20T00:10:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 1,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"005930","quantity":3,"executedPrice":70000,"partial":false}',
          createdAt: '2026-05-20T00:09:00Z',
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalledWith(
        '누락된 이벤트 108건 감지 — 최근 100건만 표시. 매매일지·주문 내역 확인 권장.',
        'warning',
      )
    })

    expect(mocks.list).toHaveBeenCalledWith(userId, since, 100, 'order')
    expect(mocks.showToast.mock.calls.some((call) => String(call[0]).includes('체결 완료'))).toBe(true)
    expect(localStorage.getItem(`stomp:lastSeenAt:${userId}`)).toBe('2026-05-20T00:10:00Z')
  })

  // ────────────────────────────────────────────────────────────
  // hotfix-3 결함 3건 회귀 가드 (사용자 dogfooding 2026-05-21 새벽)
  // ────────────────────────────────────────────────────────────

  it('결함 3-A: FILLED + qty=null·executedQty=null 옛 row 는 toast 0건 (skip)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:09:00Z',
          lastTs: '2026-05-20T00:09:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      // PR #636 머지 이전 row — quantity·executedQty 둘 다 null
      return Promise.resolve([
        {
          id: 99,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson: '{"event":"FILLED","code":"004090","partial":false}',
          createdAt: '2026-05-20T00:09:00Z',
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.list).toHaveBeenCalled()
    })

    // toast 0건 검증 — "0주" 오인 표시 회귀 가드
    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      /체결|주문/.test(String(call[0])),
    )
    expect(orderToastCalls).toHaveLength(0)
    // anchor 는 lastTs 로 갱신 — 옛 row 무한 재 backfill 차단
    expect(localStorage.getItem(`stomp:lastSeenAt:${userId}`)).toBe('2026-05-20T00:09:00Z')
  })

  it('결함 3-A: FILLED + quantity 정상 (5주) 이벤트는 정상 표시 (skip 회귀 없음)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:44:00Z',
          lastTs: '2026-05-20T00:44:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 100,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"004090","quantity":5,"executedPrice":13550,"partial":false}',
          createdAt: '2026-05-20T00:44:00Z',
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalled()
    })

    const orderToastCalls = mocks.showToast.mock.calls.filter((call) =>
      String(call[0]).includes('체결 완료'),
    )
    expect(orderToastCalls).toHaveLength(1)
    expect(String(orderToastCalls[0][0])).toContain('004090')
    expect(String(orderToastCalls[0][0])).toContain('5주')
    expect(String(orderToastCalls[0][0])).toContain('13,550')
  })

  it('결함 3-B: missedCount=0 이어도 lastSeenAt 을 now 로 갱신 (anchor 누적 보장)', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) =>
      Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel }),
    )

    const before = localStorage.getItem(`stomp:lastSeenAt:${userId}`)
    expect(before).toBe(since)

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.getUnreadCount).toHaveBeenCalledTimes(3)
    })

    // 새로고침마다 anchor 가 now 로 전진해야 옛 이벤트 재 backfill 무한루프 차단
    const after = localStorage.getItem(`stomp:lastSeenAt:${userId}`)
    expect(after).not.toBe(since)
    expect(after).not.toBeNull()
    // ISO 형식 + since(2026-05-19) 보다 미래여야 함
    if (after) {
      expect(Date.parse(after)).toBeGreaterThan(Date.parse(since))
    }
  })

  it('결함 3-C: toast message 에 "[누락]" prefix 가 없고 시각 prefix 가 있다', async () => {
    mocks.getUnreadCount.mockImplementation((_userId: number, _since: string, channel: string) => {
      if (channel === 'order') {
        return Promise.resolve({
          count: 1,
          firstTs: '2026-05-20T00:44:00Z',
          lastTs: '2026-05-20T00:44:00Z',
          since,
          channel,
        })
      }
      return Promise.resolve({ count: 0, firstTs: null, lastTs: null, since, channel })
    })
    mocks.list.mockImplementation((_userId: number, _since: string, _limit: number, channel: string) => {
      if (channel !== 'order') return Promise.resolve([])
      return Promise.resolve([
        {
          id: 200,
          userId,
          channel: 'order',
          eventType: 'FILLED',
          payloadJson:
            '{"event":"FILLED","code":"004090","quantity":5,"executedPrice":13550,"partial":false}',
          createdAt: '2026-05-20T00:44:00Z',
        },
      ])
    })

    renderHook(() => useStompReconnect({ userId }))

    await waitFor(() => {
      expect(mocks.showToast).toHaveBeenCalled()
    })

    const fillToast = mocks.showToast.mock.calls.find((call) =>
      String(call[0]).includes('체결 완료'),
    )
    expect(fillToast).toBeDefined()
    if (fillToast) {
      const message = String(fillToast[0])
      // "[누락]" prefix 제거 회귀 가드
      expect(message).not.toContain('[누락]')
      // KST HH:mm prefix 패턴 검증 (2026-05-20T00:44:00Z = KST 09:44)
      expect(message).toMatch(/^\[\d{2}:\d{2}\]\s/)
      expect(message).toContain('09:44')
    }
  })
})
