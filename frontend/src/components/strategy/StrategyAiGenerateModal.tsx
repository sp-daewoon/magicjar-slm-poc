/**
 * StrategyAiGenerateModal — 빌더 AI 전략 생성 팝업
 *
 * AI 프리셋 추천 팝업(PR #778) UX 패턴의 2번째 이식.
 *   1. 전략 의도(prompt) textarea 필수 + interval/제안 수 선택
 *   2. "생성하기" → backend POST /api/strategies/ai-generate (Claude — 최대 수 분)
 *   3. 제안 카드: name·description·entryDsl/exitDsl·riskPlan·rationale + "빌더로 불러오기"
 *      - dslValid=false 카드: dslError 표시 + 불러오기 비활성
 *      - warnings 표시
 *   4. 불러오기 → onLoadDraft(item) — 부모가 빌더 폼 상태에 주입 (자동 저장 0)
 *
 * ADR 0112 매매 결정권 0 — 저장은 사용자가 검토·백테스트 후 직접 수행.
 * 룰 5: LLM provider import 0건 — backend endpoint 호출만.
 */
import { useState, useRef, useEffect } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useStrategyBuilderAiGenerate } from '../../api/hooks/useStrategyBuilderAiGenerate'
import type { StrategyBuilderAiGenerateItem } from '../../api/strategyBuilderAiGenerate'

const INTERVAL_OPTIONS = [
  '1m', '3m', '5m', '15m', '30m', '90m', '1h', '4h', '12h', '1d', '1wk', '1mo', '1y',
]
const COUNT_OPTIONS = [1, 2, 3]

interface StrategyAiGenerateModalProps {
  /** 등록된 사용자 ID (onboarding) */
  userId: number | null
  /** 빌더 현재 interval — 기본 선택값 */
  defaultInterval: string
  /** "빌더로 불러오기" — 부모가 폼 상태에 주입 (자동 저장 0) */
  onLoadDraft: (item: StrategyBuilderAiGenerateItem) => void
  /** 모달 닫기 */
  onClose: () => void
}

function formatRiskPlan(item: StrategyBuilderAiGenerateItem): string {
  const parts: string[] = []
  if (item.riskPlan.takeProfitPct != null) parts.push(`익절 ${item.riskPlan.takeProfitPct}%`)
  if (item.riskPlan.fixedStopLossPct != null) parts.push(`손절 ${item.riskPlan.fixedStopLossPct}%`)
  if (item.riskPlan.minHoldBars != null) parts.push(`최소보유 ${item.riskPlan.minHoldBars}봉`)
  return parts.length > 0 ? parts.join(' · ') : '리스크 플랜 미지정'
}

export function StrategyAiGenerateModal({
  userId,
  defaultInterval,
  onLoadDraft,
  onClose,
}: StrategyAiGenerateModalProps) {
  const [prompt, setPrompt] = useState('')
  const [interval, setInterval] = useState(defaultInterval)
  const [count, setCount] = useState(1)
  const [promptError, setPromptError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const { mutate, data: result, isPending, error, reset } = useStrategyBuilderAiGenerate()

  // ESC 닫기 + body scroll lock (공용 훅 — IME 조합 중 ESC 무시)
  useModalBehavior({ open: true, onClose })

  useEffect(() => {
    textareaRef.current?.focus()
  }, [])

  const handleGenerate = () => {
    const trimmed = prompt.trim()
    if (!trimmed) {
      setPromptError('전략 의도를 입력하세요')
      return
    }
    if (userId == null) {
      setPromptError('사용자 등록 후 이용할 수 있습니다')
      return
    }
    setPromptError(null)
    mutate({ userId: String(userId), prompt: trimmed, interval, count })
  }

  const errorMessage = (() => {
    if (!error) return null
    const status = (error as { response?: { status?: number } })?.response?.status
    if (status === 503) {
      return 'AI 서비스를 사용할 수 없습니다 (503). Claude CLI/브릿지 설정이 필요합니다.'
    }
    return `전략 생성 실패: ${error.message}`
  })()

  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="AI 전략 생성"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="max-h-[88vh] w-full max-w-2xl overflow-y-auto rounded-lg border border-violet-900/60 bg-gray-900 shadow-xl">
        {/* 헤더 */}
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-800 bg-gray-900 px-4 py-3">
          <div>
            <h2 className="text-sm font-semibold text-violet-100">AI 전략 생성</h2>
            <p className="mt-0.5 text-xs text-gray-500">
              전략 의도를 적으면 Claude가 빌더에 바로 불러올 수 있는 후보를 설계합니다
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded border border-gray-700 px-2.5 py-1 text-xs text-gray-300 hover:bg-gray-800"
            aria-label="모달 닫기"
          >
            닫기
          </button>
        </div>

        <div className="space-y-3 p-4">
          {/* 전략 의도 */}
          <label className="block">
            <span className="text-xs text-gray-500">전략 의도 (필수)</span>
            <textarea
              ref={textareaRef}
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value)
                if (promptError) setPromptError(null)
              }}
              disabled={isPending}
              rows={3}
              aria-label="전략 의도 (필수)"
              placeholder="예: 거래량이 터지면서 20일선을 돌파하는 단타 / 과매도 반등 스윙"
              className="mt-1 w-full resize-none rounded border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-100 placeholder-gray-600 focus:border-violet-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
              onKeyDown={(e) => {
                if (e.ctrlKey && e.key === 'Enter') handleGenerate()
              }}
            />
          </label>

          {/* 옵션 — interval + 제안 수 */}
          <div className="flex flex-wrap items-end gap-3">
            <label className="block">
              <span className="text-xs text-gray-500">기준봉</span>
              <select
                value={interval}
                onChange={(e) => setInterval(e.target.value)}
                disabled={isPending}
                aria-label="기준봉"
                className="mt-1 rounded border border-gray-700 bg-gray-950 px-2 py-1.5 text-sm text-gray-100 focus:border-violet-500 focus:outline-none disabled:opacity-60"
              >
                {INTERVAL_OPTIONS.map((iv) => (
                  <option key={iv} value={iv}>{iv}</option>
                ))}
              </select>
            </label>
            <label className="block">
              <span className="text-xs text-gray-500">제안 수</span>
              <select
                value={count}
                onChange={(e) => setCount(Number(e.target.value))}
                disabled={isPending}
                aria-label="제안 수"
                className="mt-1 rounded border border-gray-700 bg-gray-950 px-2 py-1.5 text-sm text-gray-100 focus:border-violet-500 focus:outline-none disabled:opacity-60"
              >
                {COUNT_OPTIONS.map((c) => (
                  <option key={c} value={c}>{c}개</option>
                ))}
              </select>
            </label>
            <button
              type="button"
              onClick={handleGenerate}
              disabled={isPending}
              className="rounded bg-violet-600 px-4 py-2 text-sm font-semibold text-white hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {isPending ? '생성 중' : '생성하기'}
            </button>
            {result && <span className="text-xs text-gray-500">{result.provider}</span>}
          </div>

          {promptError && (
            <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200" role="alert">
              {promptError}
            </div>
          )}

          {/* 로딩 */}
          {isPending && (
            <div
              className="rounded border border-violet-900 bg-violet-950/20 px-3 py-3"
              role="status"
              aria-live="polite"
            >
              <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                <span className="font-medium text-violet-100">Claude가 전략을 설계 중입니다</span>
                <span className="text-violet-300">최대 수 분까지 기다립니다</span>
              </div>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-gray-800">
                <div className="ai-discovery-loading-bar h-full w-1/3 rounded-full bg-violet-400" />
              </div>
            </div>
          )}

          {errorMessage && (
            <div className="rounded border border-red-900 bg-red-950/30 px-3 py-2 text-sm text-red-200" role="alert">
              {errorMessage}
            </div>
          )}

          {/* warnings */}
          {result && result.warnings.length > 0 && (
            <div className="rounded border border-amber-900 bg-amber-950/20 px-3 py-2 text-xs text-amber-200">
              {result.warnings.join(' · ')}
            </div>
          )}

          {/* 제안 카드 */}
          {result && (
            result.items.length === 0 ? (
              <div className="rounded border border-gray-800 bg-gray-950 px-4 py-8 text-center text-sm text-gray-500">
                생성된 후보가 없습니다. 의도를 더 구체적으로 적고 다시 시도해보세요.
              </div>
            ) : (
              <div className="space-y-3">
                {result.items.map((item, index) => (
                  <div
                    key={`${item.name}-${index}`}
                    className="rounded border border-gray-800 bg-gray-950 p-3"
                    data-testid="ai-strategy-card"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-sm font-semibold text-gray-100">{item.name}</div>
                        <p className="mt-1 text-xs leading-5 text-gray-400">{item.description}</p>
                      </div>
                      <button
                        type="button"
                        onClick={() => onLoadDraft(item)}
                        disabled={!item.dslValid}
                        title={item.dslValid ? undefined : 'DSL 검증에 실패한 후보는 불러올 수 없습니다'}
                        className={[
                          'shrink-0 whitespace-nowrap rounded px-3 py-1.5 text-xs font-semibold',
                          item.dslValid
                            ? 'bg-violet-600 text-white hover:bg-violet-700'
                            : 'cursor-not-allowed border border-gray-700 bg-gray-900 text-gray-600',
                        ].join(' ')}
                      >
                        빌더로 불러오기
                      </button>
                    </div>

                    {/* DSL 코드 블록 */}
                    <div className="mt-2 space-y-2">
                      <div>
                        <div className="text-[11px] font-medium text-gray-500">매수 조건 (Entry)</div>
                        <pre className="mt-1 max-h-32 overflow-y-auto whitespace-pre-wrap break-words rounded border border-gray-800 bg-gray-900/60 px-2 py-1.5 text-xs leading-5 text-emerald-200">
                          {item.entryDsl}
                        </pre>
                      </div>
                      {item.exitDsl && (
                        <div>
                          <div className="text-[11px] font-medium text-gray-500">매도 조건 (Exit)</div>
                          <pre className="mt-1 max-h-32 overflow-y-auto whitespace-pre-wrap break-words rounded border border-gray-800 bg-gray-900/60 px-2 py-1.5 text-xs leading-5 text-rose-200">
                            {item.exitDsl}
                          </pre>
                        </div>
                      )}
                    </div>

                    {/* riskPlan 요약 */}
                    <div className="mt-2 text-xs text-gray-400">{formatRiskPlan(item)}</div>

                    {/* rationale */}
                    {item.rationale && (
                      <p className="mt-2 text-xs leading-5 text-gray-500">{item.rationale}</p>
                    )}

                    {/* dslValid=false — 검증 실패 사유 */}
                    {!item.dslValid && (
                      <div className="mt-2 rounded border border-red-900 bg-red-950/30 px-2 py-1.5 text-xs text-red-200">
                        DSL 검증 실패{item.dslError ? `: ${item.dslError}` : ''} — 불러오기가 비활성화되었습니다.
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )
          )}

          {/* AI 면책 */}
          <p className="text-xs text-gray-600">
            AI 생성은 보조 기능입니다. 자동 저장하지 않으며, 검토·백테스트 후 직접 저장하세요.
            {result && (
              <button
                type="button"
                onClick={() => reset()}
                className="ml-2 text-violet-400 hover:text-violet-300"
              >
                다시 생성
              </button>
            )}
          </p>
        </div>
      </div>
    </div>
  )
}
