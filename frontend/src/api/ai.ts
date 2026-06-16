/**
 * AI Explain REST + STOMP API client (T-AI R2 frontend — W4 R2 dispatch §2-2)
 *
 * Backend 1:1 정합 endpoint (R1 머지 — PR #678 ai-engineer):
 * - POST /api/ai/explain → AiExplainResponse (즉시 ACCEPTED|REFUSED · ~10ms)
 *
 * STOMP topic (AiCommentResponseStompForwarder):
 * - /topic/ai.explain.{requestId} → AiResponseEnvelope push (LLM 응답 도달 시 1회)
 *
 * 매매 결정권 0 (ADR 0026 정합) — 본 모듈은 advisory only.
 * graceful — `MAGICJAR_AI_ENABLED=false` 시 status=REFUSED + userMessage 한국어.
 */
import apiClient from '../lib/axios'

const AI_EXPLAIN_TIMEOUT_MS = 310_000

// ──────────────────────────────────────────────
// 5 진입점 type (backend AiCommentType enum 1:1)
// ──────────────────────────────────────────────

export type AiCommentType = 'SYMBOL' | 'CHART' | 'STRATEGY' | 'SIGNAL' | 'MARKET'

/** type 한국어 라벨 — AiCard 헤더 + 진입점 버튼 텍스트 공용. */
export const AI_TYPE_LABELS: Record<AiCommentType, string> = {
  SYMBOL: 'AI 종목 분석',
  CHART: '차트 해석',
  STRATEGY: '전략 평가',
  SIGNAL: '시그널 해석',
  MARKET: '시장 추세 해석',
}

// ──────────────────────────────────────────────
// REST DTO (backend AiExplainDto 1:1)
// ──────────────────────────────────────────────

export interface AiExplainRequest {
  type: AiCommentType
  userId: number
  /** prompt 변수 치환 입력 — type 별 V50 seed variables_schema 정합. */
  context: Record<string, unknown>
}

export type AiExplainStatus = 'ACCEPTED' | 'REFUSED'

export type AiRefuseCode =
  | 'KILLSWITCH_ENGAGED'
  | 'AI_DISABLED'
  | 'DAILY_TOKEN_LIMIT_EXCEEDED'
  | 'PROMPT_NOT_FOUND'

export interface AiExplainResponse {
  requestId: string
  status: AiExplainStatus
  refuseCode?: AiRefuseCode | null
  userMessage?: string | null
}

// ──────────────────────────────────────────────
// STOMP envelope (consumer AiResponseEnvelope 1:1 schema v1)
// ──────────────────────────────────────────────

export interface AiResponseEnvelope {
  eventId: string
  requestId: string
  userId: number
  commentType: AiCommentType
  provider: string
  model: string
  promptTemplate: string
  content: string
  tokensIn: number
  tokensOut: number
  latencyMs: number
  /** BigDecimal scale=6 string (예: "0.012345"). */
  costUsd: string
  cached: boolean
  graceful: boolean
  errorMessage?: string | null
  occurredAt: string
  schemaVersion: number
}

// ──────────────────────────────────────────────
// REST 호출
// ──────────────────────────────────────────────

/** POST /api/ai/explain — AI 코멘트 요청 (비동기 ACCEPTED). */
export async function requestAiExplain(
  request: AiExplainRequest,
): Promise<AiExplainResponse> {
  const { data } = await apiClient.post<AiExplainResponse>('/ai/explain', request, {
    timeout: AI_EXPLAIN_TIMEOUT_MS,
  })
  return data
}
