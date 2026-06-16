/**
 * 발굴 → MOCK 페이퍼 트레이딩 REST 클라이언트 (ADR 0122 R3 · spec §3-R2 계약 LOCK)
 *
 *   POST   /api/daytrade/paper/promote     — 발굴 setup → MOCK 페이퍼 전략 일괄 승격(사용자 opt-in)
 *   DELETE /api/daytrade/paper/{strategyId} — 페이퍼 전략 중단(soft delete)
 *   GET    /api/daytrade/paper/strategies   — 활성 페이퍼 전략 목록
 *   GET    /api/daytrade/paper/positions    — 페이퍼 포지션·실현/평가손익
 *
 * 타입은 backend DaytradePaperDto.kt 와 1:1 정합 (필드명 추측 금지 — DTO 직접 인용).
 *
 * ### 가드 (방향성 보존 — ADR 0122)
 * - 룰 기반 — LLM 호출 0. 페이퍼는 기존 signal→risk→order 경로 + MOCK 강제(실거래 결정권 0).
 * - 자동 진입 0 — promote 는 사용자 명시 액션(승급 버튼)에서만 호출. 발굴 결과 표시·cron 자동 승격 없음.
 * - 페이퍼 ≠ 실거래 — 가상 자본 기준 모의. UX 에서 "모의 — 실거래 아님" 배지 의무(§6).
 * - mock 합성 0 — 빈 응답은 "데이터 없음"으로 정직 표기(feedback_no_mock_data).
 */
import apiClient from '../../../lib/axios'

// ──────────────────────────────────────────────
// Request / Response 타입 (백엔드 DaytradePaperDto.kt 1:1)
// ──────────────────────────────────────────────

/** POST /api/daytrade/paper/promote 요청 (PaperPromoteRequest.kt 정합) */
export interface PaperPromoteRequest {
  /** 전략 소유자 사용자 ID */
  userId: number
  /** 발굴 빌트인 setup key (strategy_research_candidate.candidate_key) */
  presetKey: string
  /** 페이퍼로 감시할 종목 코드 목록 (발굴 결과 TOP N 중 사용자 선택). 빈 리스트 reject. */
  codes: string[]
  /** 장전 setup 기준 인터벌 (default 1d) */
  interval?: string
  /** 가상 자본 초기 금액(원) 사용자 override. null/생략이면 ENV default(1천만원). */
  virtualCapitalKrw?: number | null
}

/** 페이퍼(MOCK) 전략 DTO (PaperStrategyDto.kt 1:1 — 실거래 아님) */
export interface PaperStrategyDto {
  /** 페이퍼 전략 ID */
  strategyId: number
  /** 전략 출처 — 항상 PAPER_DISCOVERY */
  origin: string
  /** 발굴 setup key */
  presetKey: string
  /** 발굴 setup 제목 */
  presetTitle: string
  /** 페이퍼 감시 종목 */
  codes: string[]
  /** 평가 인터벌 */
  intervals: string[]
  /** 가상 자본 초기 금액(원) */
  virtualCapital: number
  /** 전략 상태 (ACTIVE / DELETED 등) */
  status: string
  /** 생성/갱신 시각(ISO-8601) */
  updatedAt: string
}

/** 페이퍼(가상) 포지션 DTO (PaperPositionDto.kt 1:1 — 실 계좌 아님, 손익은 가상 ₩) */
export interface PaperPositionDto {
  /** 페이퍼 전략 ID */
  strategyId: number
  /** 사용자 ID */
  userId: string
  /** 종목 코드 */
  code: string
  /** 종목명 */
  codeName: string | null
  /** 보유 수량(가상) */
  quantity: number
  /** 평단가(가상 ₩) */
  avgPrice: number
  /** 진행 회차(분할 lot) */
  filledLots: number
  /** 실현손익(가상 ₩) */
  realizedPnl: number
  /** 청산 완료 여부(quantity=0) */
  isClosed: boolean
  /** 갱신 시각(ISO-8601) */
  updatedAt: string
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const daytradePaperApi = {
  /** 발굴 setup → MOCK 페이퍼 전략 일괄 승격 (사용자 명시 opt-in) */
  promote: async (req: PaperPromoteRequest): Promise<PaperStrategyDto> => {
    const res = await apiClient.post<PaperStrategyDto>('/daytrade/paper/promote', req)
    return res.data
  },

  /** 페이퍼 전략 중단 (soft delete) */
  stop: async (strategyId: number, userId: number): Promise<void> => {
    await apiClient.delete(`/daytrade/paper/${strategyId}`, { params: { userId } })
  },

  /** 활성 페이퍼 전략 목록 */
  listStrategies: async (userId: number): Promise<PaperStrategyDto[]> => {
    const res = await apiClient.get<PaperStrategyDto[]>('/daytrade/paper/strategies', {
      params: { userId },
    })
    return res.data
  },

  /** 페이퍼 포지션·손익 조회 */
  listPositions: async (userId: number): Promise<PaperPositionDto[]> => {
    const res = await apiClient.get<PaperPositionDto[]>('/daytrade/paper/positions', {
      params: { userId },
    })
    return res.data
  },
}
