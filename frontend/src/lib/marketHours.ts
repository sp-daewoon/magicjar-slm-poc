/**
 * 한국 주식시장 거래 시간 헬퍼
 *
 * KRX 운영 시간 매트릭스 (KST, 평일):
 *   - 정규장(REGULAR)       : 09:00 ~ 15:30
 *   - 시간외 단일가(AFTER_HOURS): 16:00 ~ 18:00
 *   - NXT 야간(NXT)         : 18:00 ~ 20:00  (NXT 상장 종목 한정)
 *   - 그 외 시간 / 주말 / 공휴일 → CLOSED
 *
 * 공휴일은 별도 API(T-MS1 MarketCalendar)와 연동 가능하나
 * 이 헬퍼는 클라이언트 독립 판정용 — 시간대 기반 fast path만 구현.
 *
 * dogfooding hotfix (2026-05-21):
 *   - 결함 A: getPriceBadge fall-through default가 무조건 'delayed' → 마감 시간에도 노란 "지연 데이터" 표시
 *   - 결함 B: isKoreanMarketOpen이 정규장만 인식 → 시간외·NXT 미반영
 *   - fix: currentKrxSession enum 도입 + getPriceBadge fall-through를 시간 기반 'closed' fallback으로 교체
 */

/** KRX 세션 분류 */
export type KrxSession = 'REGULAR' | 'AFTER_HOURS' | 'NXT' | 'CLOSED'

/** 주어진 시각을 KST 분(요일·시·분)으로 분해 */
function toKstParts(now: Date): { dayOfWeek: number; totalMinutes: number } {
  // KST = UTC+9
  const kstMs = now.getTime() + 9 * 60 * 60 * 1000
  const kstDate = new Date(kstMs)
  const dayOfWeek = kstDate.getUTCDay() // 0=일, 1=월 ... 6=토
  const hours = kstDate.getUTCHours()
  const minutes = kstDate.getUTCMinutes()
  return { dayOfWeek, totalMinutes: hours * 60 + minutes }
}

/**
 * 현재 KRX 세션 분류.
 *
 *   09:00 ~ 15:30 → REGULAR
 *   16:00 ~ 18:00 → AFTER_HOURS  (시간외 단일가)
 *   18:00 ~ 20:00 → NXT          (NXT 야간 — 종목별 가용 여부는 별도)
 *   그 외 / 주말 → CLOSED
 */
export function currentKrxSession(now: Date = new Date()): KrxSession {
  const { dayOfWeek, totalMinutes } = toKstParts(now)
  if (dayOfWeek === 0 || dayOfWeek === 6) return 'CLOSED' // 주말

  // 09:00=540, 15:30=930, 16:00=960, 18:00=1080, 20:00=1200
  if (totalMinutes >= 540 && totalMinutes < 930) return 'REGULAR'
  if (totalMinutes >= 960 && totalMinutes < 1080) return 'AFTER_HOURS'
  if (totalMinutes >= 1080 && totalMinutes < 1200) return 'NXT'
  return 'CLOSED'
}

/**
 * 주어진 시각이 KRX 정규장(평일 09:00~15:30 KST)인지 판정.
 *
 * 보수 호환: 기존 caller(SymbolOrderbook 등)는 "호가 제공 시간 = 정규장" 의미로 사용 중이므로
 * 시그니처·의미를 유지. 시간외/NXT까지 포함한 거래 시간 전반 판정은
 * `isKrxTradingHours` 또는 `currentKrxSession`을 사용.
 */
export function isKoreanMarketOpen(now: Date = new Date()): boolean {
  return currentKrxSession(now) === 'REGULAR'
}

/**
 * KRX 거래 가능 시간(정규장 + 시간외 단일가 + NXT) 통합 판정.
 * 가격 표시 배지의 "지금이 거래 시간인가?" 판단용.
 */
export function isKrxTradingHours(now: Date = new Date()): boolean {
  return currentKrxSession(now) !== 'CLOSED'
}

/**
 * asOf ISO 문자열이 30초 이내(실시간 갱신 기준)인지 판정
 * @param asOf ISO 8601 문자열
 * @param now 현재 시각 (기본: Date.now())
 */
export function isWithin30Seconds(asOf: string, now: Date = new Date()): boolean {
  if (!asOf) return false
  const asOfMs = new Date(asOf).getTime()
  if (isNaN(asOfMs)) return false
  return now.getTime() - asOfMs <= 30_000
}

/**
 * 현재 가격 소스 + 시각을 바탕으로 표시 배지 종류 결정
 *
 * 반환값:
 *   'live'    — asOf가 30초 이내 (실시간, 녹색)
 *   'delayed' — 거래 시간 중인데 30초 초과 (지연, 노랑)
 *   'closed'  — 거래 시간 외 (장 마감, 회색)
 *
 * 결정 우선순위:
 *   1. asOf가 30초 이내 → 'live' (source 무관)
 *   2. source==='closed' (서버가 명시적으로 마감 표기) → 'closed'
 *   3. 시간 기반 판정: 거래 시간(REGULAR/AFTER_HOURS/NXT) 외면 → 'closed'
 *   4. 그 외(거래 시간 중인데 30초 초과) → 'delayed'
 *
 * dogfooding hotfix (2026-05-21):
 *   - 기존: source가 'polling'·'live'·'closed' 외 (undefined/unknown) 시 무조건 'delayed' fall-through
 *     → 23:25 KST 마감 시간에도 노란 "지연 데이터" 라벨 표시 (사용자 dogfooding 결함 A)
 *   - 변경: fall-through default를 시간 기반 판정으로 교체 — 거래 시간 외면 'closed'가 안전 default.
 *           source 신뢰성보다 시간 매트릭스가 사용자 mental model에 정합.
 */
export type PriceBadgeType = 'live' | 'delayed' | 'closed'

export function getPriceBadge(
  source: string,
  asOf: string,
  now: Date = new Date(),
): PriceBadgeType {
  // 1. asOf 30초 이내면 무조건 live (source 무관 — 실시간 tick 도착의 최우선 근거)
  if (isWithin30Seconds(asOf, now)) return 'live'

  // 2. 서버가 명시적으로 closed 표기
  if (source === 'closed') return 'closed'

  // 3·4. 시간 기반 판정 (fall-through default — source가 polling·live·undefined·unknown 무관)
  //      - 거래 시간 외 → 'closed' (★ 결함 A fix: 마감 시간에 '지연' 오표기 방지)
  //      - 거래 시간 중 → 'delayed' (실시간 끊김 / polling 갱신 / live source 30초 초과 통합 처리)
  return isKrxTradingHours(now) ? 'delayed' : 'closed'
}
