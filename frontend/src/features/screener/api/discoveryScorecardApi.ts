/**
 * 발굴 스코어카드 REST API 클라이언트 (Flyway V77 + DiscoveryScorecardScheduler)
 *
 *   GET /api/screener/scorecard — 발굴 forward-test 요약 + 상세 (read-only)
 *
 * 매일 장전(08:30 KST) 발굴 TOP N을 기록하고 다음 거래일(23:30 KST) 실제 일봉으로
 * 자동 채점한 "살아있는 증거"를 조회한다. 백테스트(과거)와 달리 forward 검증 데이터다.
 *
 * ### 가드 (방향성 보존)
 * - read-only — 자동매매 결정권 0, watchlist write 0, KIS 호출 0.
 * - 미채점 row(일봉 미수집 등)는 채점 필드 null로 정직 노출 (mock 합성 0).
 * - 룰 기반 — LLM 호출 0.
 */
import apiClient from '../../../lib/axios'

/** 발굴 forward-test 요약 통계 (전체 또는 프리셋 단위) — DiscoveryScorecardSummaryDto 1:1 */
export interface DiscoveryScorecardSummaryDto {
  /** 프리셋 key (전체 요약이면 null) */
  presetKey: string | null
  /** 기록된 발굴 후보 표본 수 (미채점 포함) */
  sampleCount: number
  /** 채점 완료 표본 수 */
  scoredCount: number
  /** 채점 표본의 평균 다음날 종가 수익률 % (채점 0건이면 null) — BigDecimal 직렬화 (number 또는 string) */
  avgNextReturnPct: number | string | null
  /** 채점 표본의 평균 다음날 시초가 수익률 % */
  avgNextOpenReturnPct: number | string | null
  /** hit rate % = 채점 표본 중 next_return_pct > 0 비율 (채점 0건이면 null) */
  hitRatePct: number | string | null
}

/** 발굴 스코어카드 상세 row (1 발굴 후보 = 1 row) — DiscoveryScorecardRowDto 1:1 */
export interface DiscoveryScorecardRowDto {
  /** 발굴 발사일 (= 채점 대상 거래일) */
  snapshotDate: string
  /** 발굴 프리셋 key */
  presetKey: string
  /** 랭킹 */
  rank: number
  /** 종목 코드 */
  instrumentCode: string
  /** 종목명 */
  instrumentName: string
  /** 발굴 점수 — BigDecimal 직렬화 (number 또는 string) */
  score: number | string
  /** 발굴 입력(전일) 종가 = 수익률 분모 */
  baseClose: number | string | null
  /** 다음날 시가 */
  nextOpen: number | string | null
  /** 다음날 고가 */
  nextHigh: number | string | null
  /** 다음날 종가 */
  nextClose: number | string | null
  /** 다음날 종가 수익률 % */
  nextReturnPct: number | string | null
  /** 다음날 시초가 수익률 % */
  nextOpenReturnPct: number | string | null
  /** 채점 시각 (null=미채점 — 일봉 미수집 등, mock 0) */
  scoredAt: string | null
}

/** GET /api/screener/scorecard 응답 — DiscoveryScorecardResponseDto 1:1 */
export interface DiscoveryScorecardResponseDto {
  from: string
  to: string
  presetKey: string | null
  overall: DiscoveryScorecardSummaryDto
  byPreset: DiscoveryScorecardSummaryDto[]
  rows: DiscoveryScorecardRowDto[]
}

export interface DiscoveryScorecardQuery {
  /** 조회 시작일 (포함, yyyy-MM-dd). 미지정 시 to-30일 (서버 default) */
  from?: string
  /** 조회 종료일 (포함, yyyy-MM-dd). 미지정 시 오늘(KST) */
  to?: string
  /** 프리셋 key 필터 (null/미지정이면 전체 DAYTRADE_* 프리셋) */
  presetKey?: string | null
  /** 상세 rows 최대 개수 (1~2000, default 500) */
  maxRows?: number
}

export const discoveryScorecardApi = {
  /** GET /api/screener/scorecard */
  get: async (query: DiscoveryScorecardQuery): Promise<DiscoveryScorecardResponseDto> => {
    const params = new URLSearchParams()
    if (query.from) params.set('from', query.from)
    if (query.to) params.set('to', query.to)
    if (query.presetKey) params.set('presetKey', query.presetKey)
    if (query.maxRows !== undefined) params.set('maxRows', String(query.maxRows))
    const qs = params.toString()
    const res = await apiClient.get<DiscoveryScorecardResponseDto>(
      `/screener/scorecard${qs ? `?${qs}` : ''}`,
    )
    return res.data
  },
}
