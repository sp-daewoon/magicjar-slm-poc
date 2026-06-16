/**
 * Pattern REST API 클라이언트 + DTO 정의
 *
 * spec PR-38 §R3 REST endpoint LOCK:
 *   GET /api/patterns?code=...&interval=...&patterns=...&from=...&to=...
 *
 * 응답 스키마 (PatternResponseDto):
 *   { matches: PatternMatch[] }
 *   PatternMatch: { id, name, time, direction, symbol }
 *
 * direction 분기:
 *   BULL → 상승 마커 (녹색·triangleUp·"▲")
 *   BEAR → 하락 마커 (빨강·triangleDown·"▼")
 *   NEUTRAL → 마커 제외 (R2 stub graceful — 결과 미포함)
 *
 * R1 active 10건 + R2 stub 47건 처리:
 *   - R2 stub → direction=NEUTRAL → backend 결과 미포함 → matches에 없음
 *   - 미지원 패턴 → backend graceful → matches에 없음
 *   - 캔들 부족 → backend graceful → matches에 없음
 *
 * 에러 핸들링:
 *   - 400 종목코드 형식 → 사용자 안내
 *   - 401 onboarding 미완 → SettingsModal 유도
 *   - 503 → 서비스 복구 안내
 *
 * 룰 5 (W2): LLM provider import 0건
 */
import apiClient from '../lib/axios'
import type { CandleInterval } from './symbol'

// ──────────────────────────────────────────────
// DTO 타입 (backend PatternResponseDto LOCK)
// ──────────────────────────────────────────────

/** 패턴 방향성 */
export type PatternDirection = 'BULL' | 'BEAR' | 'NEUTRAL'

/** 단일 패턴 매칭 결과 (backend PatternMatch LOCK) */
export interface PatternMatch {
  /** 패턴 코드 (예: "hammer", "engulfing") — pattern-catalog.md id LOCK */
  id: string
  /** 패턴 한국어 이름 (예: "해머") */
  name: string
  /** 패턴 발생 시점 — ISO 8601 */
  time: string
  /** 방향성 — BULL/BEAR/NEUTRAL */
  direction: PatternDirection
  /** 패턴 심볼 표시용 텍스트 (한국어 약어 또는 id) */
  symbol?: string
}

/** GET /api/patterns 응답 */
export interface PatternResponseDto {
  matches: PatternMatch[]
}

// ──────────────────────────────────────────────
// 57 패턴 카탈로그 (pattern-catalog.md KIS LOCK)
// ──────────────────────────────────────────────

/** 패턴 카테고리 분류 */
export type PatternCategory =
  | 'BULLISH_REVERSAL'
  | 'BEARISH_REVERSAL'
  | 'BULLISH_CONTINUATION'
  | 'BEARISH_CONTINUATION'
  | 'REVERSAL_DIRECTIONAL'
  | 'INDECISION'

/** 패턴 카탈로그 항목 */
export interface PatternCatalogItem {
  id: string
  nameEn: string
  nameKo: string
  category: PatternCategory
  /** 캔들 분류: single|double|triple */
  group: 'single' | 'double' | 'triple'
  /** 기본 방향성 */
  defaultDirection: PatternDirection
  /** R1 active 여부 (R1 active=true, R2 stub=false) */
  active: boolean
}

/**
 * 57 패턴 카탈로그 — pattern-catalog.md KIS LOCK
 * Single 14 + Double 16 + Triple 27 = 57
 * R1 active 10건 표시
 */
export const PATTERN_CATALOG: PatternCatalogItem[] = [
  // ──── Single Candle (14개) ────
  { id: 'doji', nameEn: 'Doji', nameKo: '도지', category: 'INDECISION', group: 'single', defaultDirection: 'NEUTRAL', active: true },
  { id: 'dragonfly_doji', nameEn: 'Dragonfly Doji', nameKo: '잠자리형 도지', category: 'BULLISH_REVERSAL', group: 'single', defaultDirection: 'BULL', active: false },
  { id: 'gravestone_doji', nameEn: 'Gravestone Doji', nameKo: '비석형 도지', category: 'BEARISH_REVERSAL', group: 'single', defaultDirection: 'BEAR', active: false },
  { id: 'long_legged_doji', nameEn: 'Long Legged Doji', nameKo: '롱 레그 도지', category: 'INDECISION', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'hammer', nameEn: 'Hammer', nameKo: '해머', category: 'BULLISH_REVERSAL', group: 'single', defaultDirection: 'BULL', active: true },
  { id: 'hanging_man', nameEn: 'Hanging Man', nameKo: '매달린 사람', category: 'BEARISH_REVERSAL', group: 'single', defaultDirection: 'BEAR', active: true },
  { id: 'inverted_hammer', nameEn: 'Inverted Hammer', nameKo: '역해머', category: 'BULLISH_REVERSAL', group: 'single', defaultDirection: 'BULL', active: true },
  { id: 'shooting_star', nameEn: 'Shooting Star', nameKo: '슈팅스타', category: 'BEARISH_REVERSAL', group: 'single', defaultDirection: 'BEAR', active: true },
  { id: 'marubozu', nameEn: 'Marubozu', nameKo: '마루보주', category: 'REVERSAL_DIRECTIONAL', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'closing_marubozu', nameEn: 'Closing Marubozu', nameKo: '종가 마루보주', category: 'REVERSAL_DIRECTIONAL', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'spinning_top', nameEn: 'Spinning Top', nameKo: '팽이', category: 'INDECISION', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'belt_hold', nameEn: 'Belt Hold', nameKo: '벨트홀드', category: 'REVERSAL_DIRECTIONAL', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'high_wave', nameEn: 'High Wave', nameKo: '높은 파도', category: 'INDECISION', group: 'single', defaultDirection: 'NEUTRAL', active: false },
  { id: 'rickshaw_man', nameEn: 'Rickshaw Man', nameKo: '인력거꾼', category: 'INDECISION', group: 'single', defaultDirection: 'NEUTRAL', active: false },

  // ──── Double Candle (16개) ────
  { id: 'engulfing', nameEn: 'Engulfing', nameKo: '잉걸핑', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: true },
  { id: 'harami', nameEn: 'Harami', nameKo: '잉태형', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'harami_cross', nameEn: 'Harami Cross', nameKo: '잉태형 도지', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'piercing', nameEn: 'Piercing', nameKo: '관통형', category: 'BULLISH_REVERSAL', group: 'double', defaultDirection: 'BULL', active: false },
  { id: 'dark_cloud_cover', nameEn: 'Dark Cloud Cover', nameKo: '먹구름형', category: 'BEARISH_REVERSAL', group: 'double', defaultDirection: 'BEAR', active: false },
  { id: 'counterattack', nameEn: 'Counterattack', nameKo: '반격형', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'on_neck', nameEn: 'On Neck', nameKo: '온넥', category: 'BEARISH_CONTINUATION', group: 'double', defaultDirection: 'BEAR', active: false },
  { id: 'in_neck', nameEn: 'In Neck', nameKo: '인넥', category: 'BEARISH_CONTINUATION', group: 'double', defaultDirection: 'BEAR', active: false },
  { id: 'thrusting', nameEn: 'Thrusting', nameKo: '관통(부분)형', category: 'BEARISH_CONTINUATION', group: 'double', defaultDirection: 'BEAR', active: false },
  { id: 'separating_lines', nameEn: 'Separating Lines', nameKo: '분리선', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'kicking', nameEn: 'Kicking', nameKo: '킥킹', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'kicking_by_length', nameEn: 'Kicking by Length', nameKo: '킥킹 (장신)', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },
  { id: 'matching_low', nameEn: 'Matching Low', nameKo: '동일 저가', category: 'BULLISH_CONTINUATION', group: 'double', defaultDirection: 'BULL', active: false },
  { id: 'gap_side_by_side_white', nameEn: 'Gap Side by Side White', nameKo: '갭 나란히 양봉', category: 'BULLISH_REVERSAL', group: 'double', defaultDirection: 'BULL', active: false },
  { id: 'homing_pigeon', nameEn: 'Homing Pigeon', nameKo: '귀가비둘기', category: 'BULLISH_REVERSAL', group: 'double', defaultDirection: 'BULL', active: false },
  { id: 'doji_star', nameEn: 'Doji Star', nameKo: '도지스타', category: 'REVERSAL_DIRECTIONAL', group: 'double', defaultDirection: 'NEUTRAL', active: false },

  // ──── Triple+ Candle (27개) ────
  { id: 'morning_star', nameEn: 'Morning Star', nameKo: '샛별', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: true },
  { id: 'morning_doji_star', nameEn: 'Morning Doji Star', nameKo: '샛별 도지', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'evening_star', nameEn: 'Evening Star', nameKo: '저녁별', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: true },
  { id: 'evening_doji_star', nameEn: 'Evening Doji Star', nameKo: '저녁별 도지', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'three_white_soldiers', nameEn: 'Three White Soldiers', nameKo: '세 흰병사', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: true },
  { id: 'three_black_crows', nameEn: 'Three Black Crows', nameKo: '세 까마귀', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: true },
  { id: 'three_inside', nameEn: 'Three Inside', nameKo: '삼중 인사이드', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'three_outside', nameEn: 'Three Outside', nameKo: '삼중 아웃사이드', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'abandoned_baby', nameEn: 'Abandoned Baby', nameKo: '버려진 아기', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'three_stars_in_south', nameEn: 'Three Stars in South', nameKo: '남쪽의 세 별', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'advance_block', nameEn: 'Advance Block', nameKo: '어드밴스 블록', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'stalled_pattern', nameEn: 'Stalled Pattern', nameKo: '정체형', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'tasuki_gap', nameEn: 'Tasuki Gap', nameKo: '타스키 갭', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'upside_gap_two_crows', nameEn: 'Upside Gap Two Crows', nameKo: '갭상승 두 까마귀', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'three_line_strike', nameEn: 'Three Line Strike', nameKo: '삼선 스트라이크', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'unique_three_river', nameEn: 'Unique Three River', nameKo: '유니크 세 강', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'breakaway', nameEn: 'Breakaway', nameKo: '브레이크어웨이', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'mat_hold', nameEn: 'Mat Hold', nameKo: '매트홀드', category: 'BULLISH_CONTINUATION', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'ladder_bottom', nameEn: 'Ladder Bottom', nameKo: '사다리 바닥', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'concealing_baby_swallow', nameEn: 'Concealing Baby Swallow', nameKo: '은폐된 베이비 스왈로', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'stick_sandwich', nameEn: 'Stick Sandwich', nameKo: '스틱 샌드위치', category: 'BULLISH_REVERSAL', group: 'triple', defaultDirection: 'BULL', active: false },
  { id: 'tristar', nameEn: 'Tristar', nameKo: '트라이스타', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'identical_three_crows', nameEn: 'Identical Three Crows', nameKo: '동일 세 까마귀', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'two_crows', nameEn: 'Two Crows', nameKo: '두 까마귀', category: 'BEARISH_REVERSAL', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'up_down_gap_three_methods', nameEn: 'Up Down Gap Three Methods', nameKo: '갭 삼법', category: 'REVERSAL_DIRECTIONAL', group: 'triple', defaultDirection: 'NEUTRAL', active: false },
  { id: 'downside_tasuki_gap', nameEn: 'Downside Tasuki Gap', nameKo: '하락 타스키 갭', category: 'BEARISH_CONTINUATION', group: 'triple', defaultDirection: 'BEAR', active: false },
  { id: 'upside_tasuki_gap', nameEn: 'Upside Tasuki Gap', nameKo: '상승 타스키 갭', category: 'BULLISH_CONTINUATION', group: 'triple', defaultDirection: 'BULL', active: false },
]

/** id로 카탈로그 항목 빠른 조회 */
export const PATTERN_CATALOG_MAP: Map<string, PatternCatalogItem> = new Map(
  PATTERN_CATALOG.map((item) => [item.id, item]),
)

/** R1 active 10건 id 집합 */
export const PATTERN_R1_ACTIVE_IDS: ReadonlySet<string> = new Set(
  PATTERN_CATALOG.filter((p) => p.active).map((p) => p.id),
)

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export interface GetPatternsParams {
  code: string
  interval: CandleInterval
  /** 쉼표 구분 패턴 ID 목록 (예: "hammer,doji,engulfing") */
  patterns?: string
  from?: string
  to?: string
}

export const patternApi = {
  /**
   * GET /api/patterns
   * 캔들스틱 패턴 인식 결과 반환 (backend JvmPatternRecognizer → Redis 캐시)
   *
   * @param opts - 패턴 요청 파라미터
   */
  getPatterns: async (opts: GetPatternsParams): Promise<PatternResponseDto> => {
    const searchParams = new URLSearchParams({
      code: opts.code,
      interval: opts.interval,
    })
    if (opts.patterns) searchParams.set('patterns', opts.patterns)
    if (opts.from) searchParams.set('from', opts.from)
    if (opts.to) searchParams.set('to', opts.to)

    const res = await apiClient.get<PatternResponseDto>(`/patterns?${searchParams.toString()}`)
    return res.data
  },
}
