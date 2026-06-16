/**
 * W4-6 TrendPreference Bean Validation 거부 4 케이스 (R3 qa §케이스 6)
 *
 * 검증:
 * - PUT /api/users/me/preferences/trend
 *   - 0개 periods → 400 Bad Request + 한국어 message
 *   - 6개 periods (max 5) → 400 + 한국어 message
 *   - 중복 periods ([5,5,20]) → 400 + 한국어 message
 *   - 253 (max 252 초과) → 400 + 한국어 message
 *
 * TrendPreference require 5건 (ADR 0101 §2-1):
 *   - size 1~5 / value 1~252 / sorted ASC / no duplicates / crossPair valid
 *
 * 회귀 가드: PR #676 (T-MARKET Phase 2) TrendPreferenceUpdateRequest 검증.
 */
import { expect, test } from '@playwright/test'

const BASE = 'http://localhost:8080/api/users/me/preferences/trend'

interface BadCase {
  name: string
  body: Record<string, unknown>
}

const BAD_CASES: BadCase[] = [
  {
    name: '0개 periods (size 0 → 1~5 require 위반)',
    body: { maPeriods: [], rsPeriod: 52 },
  },
  {
    name: '6개 periods (size 6 → 1~5 require 위반)',
    body: { maPeriods: [3, 7, 21, 60, 120, 250], rsPeriod: 52 },
  },
  {
    name: '중복 periods ([5,5,20] → no duplicates require 위반)',
    body: { maPeriods: [5, 5, 20], rsPeriod: 52 },
  },
  {
    name: '253 (value > 252 require 위반)',
    body: { maPeriods: [10, 30, 253], rsPeriod: 52 },
  },
]

test.describe('W4-6 TrendPreference Bean Validation 거부', () => {
  for (const c of BAD_CASES) {
    test(c.name, async ({ request }) => {
      const res = await request.put(BASE, {
        data: c.body,
        failOnStatusCode: false,
      })

      // 401/403 (인증 미설정) 시 skip — 단일 사용자 default authentication 의존
      if (res.status() === 401 || res.status() === 403) {
        test.skip(true, `인증 실패 (status=${res.status()}) — single-user authentication path`)
        return
      }

      // Bean Validation 거부 → 400 Bad Request
      expect(res.status()).toBe(400)

      const body = await res.json().catch(() => null)
      if (body) {
        // 한국어 message contract — 응답 body 내 한국어 문자 1자 이상 포함 검증
        const text = JSON.stringify(body)
        expect(text).toMatch(/[가-힣]/) // 한국어 message contract
      }
    })
  }
})
