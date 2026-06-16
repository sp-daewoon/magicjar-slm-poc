/**
 * 전략빌더 AI 전략 생성 API 클라이언트
 *
 * backend PR #793 endpoint LOCK:
 *   POST /api/strategies/ai-generate
 *
 * Live-Context Meta-Prompt 패턴(AI 프리셋 추천 PR #777·#778)의 2번째 이식.
 * 사용자 prompt(전략 의도)를 Claude에게 보내 빌더 draft 후보를 받아온다.
 * 저장하지 않으며(AI 매매 결정권 0 · ADR 0112 정합), 빌더 draft 로드용 제안만 반환.
 *
 * 룰 5: LLM provider import 0건 — backend endpoint 호출만
 */
import apiClient from '../lib/axios'

// Claude CLI 브릿지는 수 분까지 걸릴 수 있어 AI 프리셋 추천과 동일 타임아웃 사용
const AI_GENERATE_TIMEOUT_MS = 310_000

// ──────────────────────────────────────────────
// DTO 타입 (backend StrategyBuilderAiGenerateDto.kt LOCK)
// ──────────────────────────────────────────────

/** POST /api/strategies/ai-generate 요청 */
export interface StrategyBuilderAiGenerateRequest {
  userId: string
  /** 사용자 전략 의도 자연어(필수) */
  prompt: string
  /** 타임프레임 힌트(옵션) */
  interval?: string | null
  /** 받을 전략 후보 개수(1~3, 기본 1) */
  count?: number
}

/** AI 후보 리스크 플랜 */
export interface StrategyBuilderAiRiskPlan {
  takeProfitPct: number | null
  fixedStopLossPct: number | null
  minHoldBars: number | null
}

/** AI가 설계한 전략 후보 1건 */
export interface StrategyBuilderAiGenerateItem {
  name: string
  description: string
  entryDsl: string
  exitDsl: string | null
  riskPlan: StrategyBuilderAiRiskPlan
  rationale: string
  /** entryDsl/exitDsl이 실제 DSL 파서를 통과했는지 여부 */
  dslValid: boolean
  /** DSL 검증 실패 사유(통과 시 null) */
  dslError: string | null
}

/** POST /api/strategies/ai-generate 응답 */
export interface StrategyBuilderAiGenerateResponse {
  provider: string
  generatedAt: string
  warnings: string[]
  items: StrategyBuilderAiGenerateItem[]
}

// ──────────────────────────────────────────────
// API 함수
// ──────────────────────────────────────────────

export const strategyBuilderAiGenerateApi = {
  /**
   * POST /api/strategies/ai-generate
   * 사용자 전략 의도 → AI 빌더 draft 후보 생성
   *
   * 사용자 트리거만 — 자동 호출 0 (ADR 0112)
   */
  generate: async (
    req: StrategyBuilderAiGenerateRequest,
  ): Promise<StrategyBuilderAiGenerateResponse> => {
    const body = {
      userId: req.userId,
      prompt: req.prompt.trim(),
      interval: req.interval?.trim() ? req.interval.trim() : null,
      count: req.count ?? 1,
    }
    const res = await apiClient.post<StrategyBuilderAiGenerateResponse>(
      '/strategies/ai-generate',
      body,
      { timeout: AI_GENERATE_TIMEOUT_MS },
    )
    return res.data
  },
}
