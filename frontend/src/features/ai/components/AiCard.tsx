/**
 * AiCard — 5 진입점 공통 AI 응답 카드 (T-AI R2 §2-2)
 *
 * 진입점 5종 공유:
 *   - /symbol/:code "AI 종목 분석"
 *   - /symbol/:code 차트 "차트 해석"
 *   - /strategy/:id "전략 평가"
 *   - /dashboard 시그널 카드 "이 시그널 해석"
 *   - /market/trend "시장 추세 해석"
 *
 * 상태 분기:
 *   IDLE       → 빈 상태 (trigger 버튼 + 안내)
 *   REQUESTING → 로딩 spinner
 *   WAITING    → "AI 응답 대기 중" + STOMP subscribe 표시
 *   RECEIVED   → markdown 본문 + provider/model/token/cost 메타
 *   REFUSED    → 한국어 사유 (4단계 가드)
 *   ERROR      → 네트워크/400 사유 + 재시도 권유
 *   TIMEOUT    → 장기 응답 지연 안내 + 재시도
 *
 * 매매 결정권 0 — 본 카드는 advisory only (ADR 0026 정합).
 */
import { AI_TYPE_LABELS, type AiCommentType } from '../../../api/ai'
import { type AiExplainState } from '../../../api/hooks/useAiExplain'
import { AiCardMarkdown } from './AiCardMarkdown'

interface Props {
  type: AiCommentType
  state: AiExplainState
  onTrigger: () => void
  onReset: () => void
  /** trigger 비활성 조건 (예: AI 비활성 env). default false. */
  disabled?: boolean
  /** 비활성 시 사용자 안내 메시지. */
  disabledMessage?: string
}

function CardHeader({
  type,
  onClose,
  showClose,
}: {
  type: AiCommentType
  onClose: () => void
  showClose: boolean
}) {
  return (
    <div className="flex items-center justify-between border-b border-gray-800 px-3 py-2">
      <div className="flex items-center gap-2">
        <span className="rounded bg-purple-900/60 px-1.5 py-0.5 text-[10px] font-bold text-purple-200">
          AI
        </span>
        <h3 className="text-xs font-semibold text-gray-200">{AI_TYPE_LABELS[type]}</h3>
      </div>
      {showClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="AI 카드 닫기"
          className="rounded p-0.5 text-gray-500 hover:bg-gray-800 hover:text-gray-300 focus:outline-none focus:ring-2 focus:ring-gray-600"
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            className="h-3.5 w-3.5"
            aria-hidden
          >
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      )}
    </div>
  )
}

function MetaBar({ provider, model, tokensIn, tokensOut, costUsd, cached, latencyMs }: {
  provider: string
  model: string
  tokensIn: number
  tokensOut: number
  costUsd: string
  cached: boolean
  latencyMs: number
}) {
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-t border-gray-800 px-3 py-2 text-[10px] text-gray-500">
      <span>
        provider: <strong className="text-gray-400">{provider}</strong>
      </span>
      <span className="text-gray-700">·</span>
      <span>
        model: <strong className="text-gray-400">{model}</strong>
      </span>
      <span className="text-gray-700">·</span>
      <span title="입력 토큰 / 출력 토큰">
        token: {tokensIn}/{tokensOut}
      </span>
      <span className="text-gray-700">·</span>
      <span title="USD">cost: ${costUsd}</span>
      <span className="text-gray-700">·</span>
      <span>{latencyMs}ms</span>
      {cached && (
        <span className="rounded bg-amber-900/50 px-1 py-0.5 text-[9px] text-amber-300">cache</span>
      )}
    </div>
  )
}

export function AiCard({
  type,
  state,
  onTrigger,
  onReset,
  disabled = false,
  disabledMessage,
}: Props) {
  // IDLE — trigger 버튼만 표시.
  if (state.status === 'IDLE') {
    return (
      <button
        type="button"
        onClick={onTrigger}
        disabled={disabled}
        className="inline-flex items-center gap-1.5 rounded-lg border border-purple-800 bg-purple-950/40 px-3 py-1.5 text-xs font-medium text-purple-200 transition-colors hover:bg-purple-900/60 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-purple-400"
        aria-label={`${AI_TYPE_LABELS[type]} 요청`}
        title={disabled ? disabledMessage : `${AI_TYPE_LABELS[type]}`}
      >
        <span className="text-[10px]" aria-hidden>
          AI
        </span>
        <span>{AI_TYPE_LABELS[type]}</span>
      </button>
    )
  }

  return (
    <article
      role="region"
      aria-label={`${AI_TYPE_LABELS[type]} 결과`}
      className="rounded-lg border border-purple-900/50 bg-gray-900/60 shadow-sm"
    >
      <CardHeader type={type} onClose={onReset} showClose={true} />

      {state.status === 'REQUESTING' && (
        <div className="flex items-center gap-3 px-3 py-4">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-purple-700 border-t-purple-300" />
          <span className="text-xs text-gray-400">AI에 요청 중...</span>
        </div>
      )}

      {state.status === 'WAITING' && (
        <div className="flex flex-col gap-2 px-3 py-4">
          <div className="flex items-center gap-3">
            <div className="h-4 w-4 animate-pulse rounded-full bg-purple-500" />
            <span className="text-xs text-gray-300">AI 응답 대기 중 (최대 5분)</span>
          </div>
          <p className="text-[10px] text-gray-500">request: {state.requestId.slice(0, 8)}...</p>
        </div>
      )}

      {state.status === 'RECEIVED' && (
        <>
          <div className="px-3 py-3">
            <AiCardMarkdown content={state.envelope.content} />
            {state.envelope.errorMessage && (
              <p className="mt-2 text-[11px] text-amber-300">
                ⚠ {state.envelope.errorMessage}
              </p>
            )}
          </div>
          <MetaBar
            provider={state.envelope.provider}
            model={state.envelope.model}
            tokensIn={state.envelope.tokensIn}
            tokensOut={state.envelope.tokensOut}
            costUsd={state.envelope.costUsd}
            cached={state.envelope.cached}
            latencyMs={state.envelope.latencyMs}
          />
          <div className="border-t border-gray-800 px-3 py-1.5 text-[10px] text-gray-500">
            ※ AI 코멘트는 보조 참고 자료입니다. 매매 결정은 직접 확인 후 진행하세요.
          </div>
        </>
      )}

      {state.status === 'REFUSED' && (
        <div className="flex flex-col gap-2 px-3 py-4">
          <p className="text-xs text-amber-300">{state.userMessage}</p>
          {state.refuseCode && (
            <p className="text-[10px] text-gray-500">code: {state.refuseCode}</p>
          )}
        </div>
      )}

      {state.status === 'ERROR' && (
        <div className="flex flex-col gap-2 px-3 py-4">
          <p className="text-xs text-red-300">{state.message}</p>
          <button
            type="button"
            onClick={onTrigger}
            className="self-start rounded border border-gray-700 px-2 py-1 text-[11px] text-gray-300 hover:bg-gray-800"
          >
            다시 시도
          </button>
        </div>
      )}

      {state.status === 'TIMEOUT' && (
        <div className="flex flex-col gap-2 px-3 py-4">
          <p className="text-xs text-amber-300">
            AI 응답이 5분 이상 지연되었습니다. 잠시 후 다시 시도해 주세요.
          </p>
          <p className="text-[10px] text-gray-500">request: {state.requestId.slice(0, 8)}...</p>
          <button
            type="button"
            onClick={onTrigger}
            className="self-start rounded border border-gray-700 px-2 py-1 text-[11px] text-gray-300 hover:bg-gray-800"
          >
            다시 시도
          </button>
        </div>
      )}
    </article>
  )
}
