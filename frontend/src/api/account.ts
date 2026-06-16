/**
 * Account REST API 클라이언트
 *
 * ## T-O Phase 3 (ADR 0068 §16) — 실거래 동의 토글
 * - PUT /api/user-account/real-trading-consent  → ConsentToggleResponseDto
 * - GET /api/user-account/real-trading-consent  → ConsentToggleResponseDto
 *
 * ## T-ACCT Phase 4 (ADR 0081 + spec §4-3 LOCK) — 잔고·예수금·보유 종목 조회
 * - GET /api/account/balance     → AccountBalanceDto (KIS inquire-balance output2 11 필드)
 * - GET /api/account/positions   → AccountHoldingDto[] (KIS inquire-balance output1)
 * - GET /api/account/summary     → AccountSummaryDto  (Dashboard 헤더용 5 필드)
 *
 * 룰 5: LLM provider import 0건
 * mock 합성 0 LOCK (feedback_no_mock_data) — KIS 실패 시 502/412 → frontend "데이터 없음" 한국어
 */
import apiClient from '../lib/axios'

// ─────────────────────────────────────────────────────────────
// T-O 동의 토글 DTO
// ─────────────────────────────────────────────────────────────

/** 실거래 동의 토글 요청 */
export interface ConsentToggleRequestDto {
  consented: boolean
}

/** 실거래 동의 토글 응답 (ADR 0089 Phase 2 — userId number 통일) */
export interface ConsentToggleResponseDto {
  userId: number
  /** 동의 시각 (ISO 8601). null = 동의 해제 상태 */
  realTradingConsentAt: string | null
}

// ─────────────────────────────────────────────────────────────
// T-ACCT 잔고·보유·요약 DTO (backend AccountDto.kt 1:1 매핑)
// ─────────────────────────────────────────────────────────────

/** KIS 계좌 유형 — backend AccountType enum 정합 */
export type AccountType = 'REAL' | 'MOCK'

/**
 * 계좌 잔고 응답 — KIS inquire-balance output2 11 필드 매핑.
 *
 * BigDecimal → number 직렬화 (axios JSON 파싱 — 정수·소수 둘 다 number)
 */
export interface AccountBalanceDto {
  /** 예수금 총금액 (dnca_tot_amt) */
  cashBalance: number
  /** D+1 예수금 (nxdy_excc_amt) */
  cashD1: number
  /** D+2 예수금 — 실가용 (prvs_rcdl_excc_amt) */
  cashD2: number
  /** 총 평가금액 — 자산 (tot_evlu_amt) */
  totalEvaluation: number
  /** 순자산금액 (nass_amt) */
  netAssetValue: number
  /** 매입금액 합계 (pchs_amt_smtl_amt) */
  totalPurchaseAmount: number
  /** 평가금액 합계 — 보유 종목 평가금 합 (evlu_amt_smtl_amt) */
  totalHoldingEvaluation: number
  /** 평가손익 합계 (evlu_pfls_smtl_amt) */
  totalProfitLoss: number
  /** 자산증감액 — 전일 대비 (asst_icdc_amt). nullable */
  assetIncreaseDecrease: number | null
  /** 자산증감 수익률 % (asst_icdc_erng_rt). nullable */
  assetIncreaseDecreaseRate: number | null
  /** 조회 시각 (ISO-8601 UTC) */
  fetchedAt: string
}

/** 보유 종목 응답 — KIS inquire-balance output1 매핑 */
export interface AccountHoldingDto {
  /** 종목코드 6자리 (pdno) */
  code: string
  /** 종목명 (prdt_name) */
  name: string
  /** 보유 수량 (hldg_qty) */
  quantity: number
  /** 평균 매수가 (pchs_avg_pric) */
  avgPrice: number
  /** 현재가 (prpr) */
  currentPrice: number
  /** 평가금액 (evlu_amt) */
  evaluationAmount: number
  /** 평가손익 (evlu_pfls_amt) */
  profitLoss: number
  /** 수익률 % (evlu_pfls_rt) */
  profitLossRate: number
}

/** 계좌 요약 응답 — Dashboard 헤더용 */
export interface AccountSummaryDto {
  /** 총 평가금액 (자산) */
  totalEvaluation: number
  /** 예수금 D+2 (실가용) */
  cashD2: number
  /** 평가손익 합계 */
  totalProfitLoss: number
  /** 수익률 % */
  profitLossRate: number
  /** 보유 종목 수 */
  holdingsCount: number
  /** 조회 시각 (ISO-8601 UTC) */
  fetchedAt: string
}

// ─────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────

export const accountApi = {
  // ── T-O Phase 3: 실거래 동의 토글 ──
  /**
   * PUT /api/user-account/real-trading-consent
   * 실거래 모드 동의 토글 (ADR 0068 의제 A-1 — 3중 게이트 세 번째 조건)
   *
   * consented=true  → real_trading_consent_at = NOW()
   * consented=false → real_trading_consent_at = NULL
   */
  updateRealTradingConsent: async (
    userId: number,
    req: ConsentToggleRequestDto,
  ): Promise<ConsentToggleResponseDto> => {
    const res = await apiClient.put<ConsentToggleResponseDto>(
      `/user-account/real-trading-consent?userId=${userId}`,
      req,
    )
    return res.data
  },

  /**
   * GET /api/user-account/real-trading-consent?userId=
   * 현재 동의 상태 조회
   */
  getRealTradingConsent: async (userId: number): Promise<ConsentToggleResponseDto> => {
    const res = await apiClient.get<ConsentToggleResponseDto>(
      `/user-account/real-trading-consent?userId=${userId}`,
    )
    return res.data
  },

  // ── T-ACCT Phase 4: KIS 실시간 잔고·보유·요약 ──
  /**
   * GET /api/account/balance?userId=&accountType=
   * KIS inquire-balance output2 11 필드 — 예수금·평가금·평가손익 등 계좌 합계.
   *
   * Redis cache `account:balance:{userId}:{accountType}` TTL 5m 공유, 주문 이벤트 시 서버에서 즉시 무효화.
   * mock 합성 0 LOCK — 실패 시 412(자격증명 부재) 또는 502(KIS upstream).
   */
  getBalance: async (
    userId: number,
    accountType: AccountType = 'REAL',
    options: { refresh?: boolean } = {},
  ): Promise<AccountBalanceDto> => {
    const params = new URLSearchParams({
      userId: String(userId),
      accountType,
    })
    if (options.refresh) params.set('refresh', 'true')
    const res = await apiClient.get<AccountBalanceDto>(`/account/balance?${params.toString()}`)
    return res.data
  },

  /**
   * GET /api/account/positions?userId=&accountType=
   * KIS inquire-balance output1 — 보유 종목별 평균가·현재가·평가금·손익.
   *
   * `/api/positions` (DB)와 달리 KIS 실시간 시세 포함.
   * 동일 Redis cache 재사용 (KIS 호출 0 추가).
   */
  getPositions: async (
    userId: number,
    accountType: AccountType = 'REAL',
    options: { refresh?: boolean } = {},
  ): Promise<AccountHoldingDto[]> => {
    const params = new URLSearchParams({
      userId: String(userId),
      accountType,
    })
    if (options.refresh) params.set('refresh', 'true')
    const res = await apiClient.get<AccountHoldingDto[]>(`/account/positions?${params.toString()}`)
    return res.data
  },

  /**
   * GET /api/account/summary?userId=&accountType=
   * Dashboard 헤더 — 총 평가금액 + 예수금 D+2 + 평가손익 + 수익률 + 보유 종목 수.
   * 30s polling 으로 사용.
   */
  getSummary: async (
    userId: number,
    accountType: AccountType = 'REAL',
    options: { refresh?: boolean } = {},
  ): Promise<AccountSummaryDto> => {
    const params = new URLSearchParams({
      userId: String(userId),
      accountType,
    })
    if (options.refresh) params.set('refresh', 'true')
    const res = await apiClient.get<AccountSummaryDto>(`/account/summary?${params.toString()}`)
    return res.data
  },
}
