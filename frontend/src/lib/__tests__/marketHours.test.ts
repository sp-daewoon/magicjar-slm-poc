import { describe, it, expect } from 'vitest'
import {
  isKoreanMarketOpen,
  isKrxTradingHours,
  currentKrxSession,
  isWithin30Seconds,
  getPriceBadge,
} from '../marketHours'

/**
 * KST(UTC+9) 기준 시각을 UTC Date로 만든 헬퍼.
 * Date.UTC(년,월,일,시,분)의 시각은 UTC이므로,
 * KST 09:00 = UTC 00:00 → Date.UTC(y, m, d, 0, 0)이 곧 KST 09:00.
 *
 * 사용 예: kst(2026, 5, 21, 23, 25) → 2026-05-21 23:25 KST
 */
function kst(year: number, month: number, day: number, kstHour: number, kstMinute: number = 0): Date {
  return new Date(Date.UTC(year, month - 1, day, kstHour - 9, kstMinute))
}

describe('marketHours — currentKrxSession (KRX 운영 시간 매트릭스)', () => {
  // 평일 기준: 2026-05-21 목요일
  it('정규장 09:00 KST → REGULAR', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 9, 0))).toBe('REGULAR')
  })

  it('정규장 10:00 KST → REGULAR', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 10, 0))).toBe('REGULAR')
  })

  it('정규장 15:29 KST → REGULAR (마지막 1분)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 15, 29))).toBe('REGULAR')
  })

  it('정규장 15:30 KST → CLOSED (정확히 종료)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 15, 30))).toBe('CLOSED')
  })

  it('15:45 KST (브릿지 시간) → CLOSED', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 15, 45))).toBe('CLOSED')
  })

  it('시간외 단일가 16:00 KST → AFTER_HOURS', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 16, 0))).toBe('AFTER_HOURS')
  })

  it('시간외 단일가 17:00 KST → AFTER_HOURS', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 17, 0))).toBe('AFTER_HOURS')
  })

  it('시간외 단일가 17:59 KST → AFTER_HOURS (마지막 1분)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 17, 59))).toBe('AFTER_HOURS')
  })

  it('NXT 18:00 KST → NXT (시간외 종료 + NXT 시작)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 18, 0))).toBe('NXT')
  })

  it('NXT 19:00 KST → NXT', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 19, 0))).toBe('NXT')
  })

  it('NXT 19:59 KST → NXT (마지막 1분)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 19, 59))).toBe('NXT')
  })

  it('NXT 종료 20:00 KST → CLOSED', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 20, 0))).toBe('CLOSED')
  })

  it('완전 마감 23:25 KST → CLOSED (★ 사용자 dogfooding 시각)', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 23, 25))).toBe('CLOSED')
  })

  it('새벽 03:00 KST → CLOSED', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 3, 0))).toBe('CLOSED')
  })

  it('개장 직전 08:59 KST → CLOSED', () => {
    expect(currentKrxSession(kst(2026, 5, 21, 8, 59))).toBe('CLOSED')
  })

  it('토요일 14:00 KST (정규장 시간대) → CLOSED', () => {
    // 2026-05-23은 토요일
    expect(currentKrxSession(kst(2026, 5, 23, 14, 0))).toBe('CLOSED')
  })

  it('일요일 10:00 KST → CLOSED', () => {
    // 2026-05-24는 일요일
    expect(currentKrxSession(kst(2026, 5, 24, 10, 0))).toBe('CLOSED')
  })
})

describe('marketHours — isKoreanMarketOpen (보수 호환 — 정규장만)', () => {
  it('정규장 10:00 KST → true', () => {
    expect(isKoreanMarketOpen(kst(2026, 5, 21, 10, 0))).toBe(true)
  })

  it('시간외 17:00 KST → false (정규장 의미만 유지)', () => {
    expect(isKoreanMarketOpen(kst(2026, 5, 21, 17, 0))).toBe(false)
  })

  it('NXT 19:00 KST → false (정규장 의미만 유지)', () => {
    expect(isKoreanMarketOpen(kst(2026, 5, 21, 19, 0))).toBe(false)
  })

  it('마감 23:25 KST → false', () => {
    expect(isKoreanMarketOpen(kst(2026, 5, 21, 23, 25))).toBe(false)
  })

  it('토요일 14:00 → false', () => {
    expect(isKoreanMarketOpen(kst(2026, 5, 23, 14, 0))).toBe(false)
  })
})

describe('marketHours — isKrxTradingHours (정규장 + 시간외 + NXT 통합)', () => {
  it('정규장 10:00 → true', () => {
    expect(isKrxTradingHours(kst(2026, 5, 21, 10, 0))).toBe(true)
  })

  it('시간외 17:00 → true', () => {
    expect(isKrxTradingHours(kst(2026, 5, 21, 17, 0))).toBe(true)
  })

  it('NXT 19:00 → true', () => {
    expect(isKrxTradingHours(kst(2026, 5, 21, 19, 0))).toBe(true)
  })

  it('마감 23:25 → false', () => {
    expect(isKrxTradingHours(kst(2026, 5, 21, 23, 25))).toBe(false)
  })

  it('브릿지 15:45 → false', () => {
    expect(isKrxTradingHours(kst(2026, 5, 21, 15, 45))).toBe(false)
  })

  it('토요일 14:00 → false', () => {
    expect(isKrxTradingHours(kst(2026, 5, 23, 14, 0))).toBe(false)
  })
})

describe('marketHours — isWithin30Seconds', () => {
  it('asOf 5초 전 → true', () => {
    const now = new Date('2026-05-21T14:30:00Z')
    const asOf = new Date(now.getTime() - 5_000).toISOString()
    expect(isWithin30Seconds(asOf, now)).toBe(true)
  })

  it('asOf 30초 정확히 → true (경계 포함)', () => {
    const now = new Date('2026-05-21T14:30:00Z')
    const asOf = new Date(now.getTime() - 30_000).toISOString()
    expect(isWithin30Seconds(asOf, now)).toBe(true)
  })

  it('asOf 31초 → false', () => {
    const now = new Date('2026-05-21T14:30:00Z')
    const asOf = new Date(now.getTime() - 31_000).toISOString()
    expect(isWithin30Seconds(asOf, now)).toBe(false)
  })

  it('asOf 빈 문자열 → false', () => {
    expect(isWithin30Seconds('', new Date())).toBe(false)
  })

  it('asOf invalid 문자열 → false', () => {
    expect(isWithin30Seconds('not-a-date', new Date())).toBe(false)
  })
})

describe('marketHours — getPriceBadge (★ dogfooding 결함 회귀 가드)', () => {
  // ─────────────────────────────────────────────────────────────
  // 결함 A 회귀 가드: source가 알 수 없을 때 fall-through default
  // ─────────────────────────────────────────────────────────────
  it('★ 23:25 KST 마감 + source=undefined → closed (★ 사용자 dogfooding 결함 회귀 가드)', () => {
    const now = kst(2026, 5, 21, 23, 25)
    const oldAsOf = new Date(now.getTime() - 10 * 60 * 1000).toISOString() // 10분 전
    expect(getPriceBadge(undefined as unknown as string, oldAsOf, now)).toBe('closed')
  })

  it('★ 23:25 KST 마감 + source="unknown" → closed (★ fall-through fix 검증)', () => {
    const now = kst(2026, 5, 21, 23, 25)
    const oldAsOf = new Date(now.getTime() - 10 * 60 * 1000).toISOString()
    expect(getPriceBadge('unknown', oldAsOf, now)).toBe('closed')
  })

  it('23:25 KST 마감 + source="" → closed', () => {
    const now = kst(2026, 5, 21, 23, 25)
    const oldAsOf = new Date(now.getTime() - 10 * 60 * 1000).toISOString()
    expect(getPriceBadge('', oldAsOf, now)).toBe('closed')
  })

  // ─────────────────────────────────────────────────────────────
  // 정규장 중 polling → delayed (기존 동작 유지)
  // ─────────────────────────────────────────────────────────────
  it('10:00 KST 정규장 + source=polling + asOf 5분 전 → delayed', () => {
    const now = kst(2026, 5, 21, 10, 0)
    const oldAsOf = new Date(now.getTime() - 5 * 60 * 1000).toISOString()
    expect(getPriceBadge('polling', oldAsOf, now)).toBe('delayed')
  })

  it('10:00 KST 정규장 + source=live + asOf 1분 전(30초 초과) → delayed', () => {
    const now = kst(2026, 5, 21, 10, 0)
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge('live', oldAsOf, now)).toBe('delayed')
  })

  // ─────────────────────────────────────────────────────────────
  // live (30초 이내) → live (source 무관)
  // ─────────────────────────────────────────────────────────────
  it('10:00 KST + asOf 5초 전 → live (source=live)', () => {
    const now = kst(2026, 5, 21, 10, 0)
    const recentAsOf = new Date(now.getTime() - 5_000).toISOString()
    expect(getPriceBadge('live', recentAsOf, now)).toBe('live')
  })

  it('23:25 KST 마감인데 asOf 5초 전 → live (asOf 우선, 실시간 tick 도착 근거)', () => {
    const now = kst(2026, 5, 21, 23, 25)
    const recentAsOf = new Date(now.getTime() - 5_000).toISOString()
    expect(getPriceBadge('live', recentAsOf, now)).toBe('live')
  })

  // ─────────────────────────────────────────────────────────────
  // source=closed 명시 → closed
  // ─────────────────────────────────────────────────────────────
  it('source=closed → closed (정규장 시간이어도 서버 명시 우선)', () => {
    const now = kst(2026, 5, 21, 10, 0)
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge('closed', oldAsOf, now)).toBe('closed')
  })

  // ─────────────────────────────────────────────────────────────
  // 시간외·NXT는 거래 시간이므로 delayed 처리
  // ─────────────────────────────────────────────────────────────
  it('17:00 KST 시간외 + source=polling + asOf 1분 전 → delayed', () => {
    const now = kst(2026, 5, 21, 17, 0)
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge('polling', oldAsOf, now)).toBe('delayed')
  })

  it('19:00 KST NXT + source=polling + asOf 1분 전 → delayed', () => {
    const now = kst(2026, 5, 21, 19, 0)
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge('polling', oldAsOf, now)).toBe('delayed')
  })

  // ─────────────────────────────────────────────────────────────
  // 토요일 → closed
  // ─────────────────────────────────────────────────────────────
  it('토요일 14:00 KST + source=polling → closed', () => {
    const now = kst(2026, 5, 23, 14, 0) // 토요일
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge('polling', oldAsOf, now)).toBe('closed')
  })

  it('토요일 14:00 KST + source=undefined → closed', () => {
    const now = kst(2026, 5, 23, 14, 0)
    const oldAsOf = new Date(now.getTime() - 60 * 1000).toISOString()
    expect(getPriceBadge(undefined as unknown as string, oldAsOf, now)).toBe('closed')
  })
})
