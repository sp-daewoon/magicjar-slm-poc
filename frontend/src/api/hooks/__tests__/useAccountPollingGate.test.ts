/**
 * useAccountPollingGate — 계좌 폴링 게이트 순수 함수 단위 테스트 (2026-06-08)
 *
 * resolveAccountRefetchInterval:
 *   - STOMP CONNECTED → false (폴링 off, push primary)
 *   - STOMP 미연결 → fallbackInterval (기본 60s)
 *   - fallbackInterval 인자 override 동작
 */
import { describe, it, expect } from 'vitest'
import {
  ACCOUNT_FALLBACK_POLLING_INTERVAL_MS,
  ACCOUNT_STALE_TIME_MS,
  resolveAccountRefetchInterval,
} from '../useAccountPollingGate'

describe('resolveAccountRefetchInterval', () => {
  it('STOMP CONNECTED → false (폴링 off)', () => {
    expect(resolveAccountRefetchInterval(true)).toBe(false)
  })

  it('STOMP 미연결 → 60s fallback (기본)', () => {
    expect(resolveAccountRefetchInterval(false)).toBe(60_000)
    expect(resolveAccountRefetchInterval(false)).toBe(ACCOUNT_FALLBACK_POLLING_INTERVAL_MS)
  })

  it('fallbackInterval 인자 override', () => {
    expect(resolveAccountRefetchInterval(false, 90_000)).toBe(90_000)
    // CONNECTED 면 override 무관하게 항상 false
    expect(resolveAccountRefetchInterval(true, 90_000)).toBe(false)
  })

  it('fallback 주기 30s → 60s 완화 회귀 방지', () => {
    expect(ACCOUNT_FALLBACK_POLLING_INTERVAL_MS).toBe(60_000)
    expect(ACCOUNT_FALLBACK_POLLING_INTERVAL_MS).toBeGreaterThan(30_000)
  })

  it('staleTime 상향 — fallback 주기 직전까지 fresh', () => {
    expect(ACCOUNT_STALE_TIME_MS).toBeGreaterThanOrEqual(50_000)
    expect(ACCOUNT_STALE_TIME_MS).toBeLessThan(ACCOUNT_FALLBACK_POLLING_INTERVAL_MS)
  })
})
