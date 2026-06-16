/**
 * NaturalLanguageInput — 자연어 DSL 변환 모달
 *
 * ADR 0023 §1 사용자 트리거만 — "자연어로 입력하기" 버튼 클릭만
 * ADR 0023 §2 매매 결정권 0 — DSL 텍스트만 반환
 * ADR 0023 §3·§4 mode 분기:
 *   REAL/MOCK: parseDslText → 트리 재구성 → onResult 호출
 *   UNTRANSLATABLE: reasoning 표시, 트리 변경 0
 *   DISABLED/503: AI 비활성 안내
 *
 * 룰 5: LLM provider import 0건 — backend /api/dsl/translate-from-natural 호출만
 */
import { useState, useRef, useEffect } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import { useStrategyTranslate } from '../../api/hooks/useStrategyTranslate'
import { parseDslTextToTree } from '../../lib/dsl/parser'
import type { BlockTree } from '../../lib/dsl/blockTree'

interface NaturalLanguageInputProps {
  /** 변환 성공 시 호출 — 트리 재구성 */
  onResult: (tree: Partial<BlockTree>) => void
  /** 모달 닫기 */
  onClose: () => void
}

export function NaturalLanguageInput({ onResult, onClose }: NaturalLanguageInputProps) {
  const [text, setText] = useState('')
  const [feedback, setFeedback] = useState<string | null>(null)
  const [feedbackType, setFeedbackType] = useState<'success' | 'warn' | 'error' | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const { mutate: translate, isPending } = useStrategyTranslate()

  // ESC 닫기 + body scroll lock (공용 훅 — IME 조합 중 ESC는 무시)
  useModalBehavior({ open: true, onClose })

  useEffect(() => {
    textareaRef.current?.focus()
  }, [])

  const handleTranslate = () => {
    if (!text.trim()) {
      setFeedback('변환할 내용을 입력하세요')
      setFeedbackType('warn')
      return
    }

    translate(
      { text: text.trim() },
      {
        onSuccess: (resp) => {
          switch (resp.mode) {
            case 'REAL':
            case 'MOCK': {
              if (resp.dslEntry || resp.dslExit) {
                try {
                  const tree = parseDslTextToTree(resp.dslEntry ?? '', resp.dslExit)
                  onResult(tree)
                  setFeedback(
                    resp.mode === 'MOCK'
                      ? 'MOCK 모드 — AI 옵션이 비활성 상태입니다. 샘플 결과를 반영했습니다.'
                      : '자연어를 DSL 트리로 변환했습니다',
                  )
                  setFeedbackType('success')
                  // 성공 시 잠시 후 모달 닫기
                  setTimeout(onClose, 1500)
                } catch {
                  setFeedback(
                    `DSL 변환 결과를 파싱할 수 없습니다. 원본 DSL: ${resp.dslEntry ?? '없음'}`,
                  )
                  setFeedbackType('error')
                }
              } else {
                setFeedback('변환 결과가 없습니다')
                setFeedbackType('warn')
              }
              break
            }
            case 'UNTRANSLATABLE': {
              setFeedback(
                resp.reasoning
                  ? `변환할 수 없습니다: ${resp.reasoning}`
                  : '입력을 DSL로 변환할 수 없습니다. 더 구체적으로 입력해 주세요.',
              )
              setFeedbackType('warn')
              break
            }
            case 'DISABLED': {
              setFeedback(
                '자연어 변환은 AI 옵션 기능입니다. 설정에서 MAGICJAR_AI_ENABLED=true로 활성화하세요.',
              )
              setFeedbackType('error')
              break
            }
          }
        },
        onError: (err) => {
          const status = (err as { response?: { status?: number } })?.response?.status
          if (status === 503) {
            setFeedback(
              'AI 서비스를 사용할 수 없습니다 (503). MAGICJAR_AI_ENABLED=true 설정이 필요합니다.',
            )
          } else {
            setFeedback(`변환 요청 실패: ${err.message}`)
          }
          setFeedbackType('error')
        },
      },
    )
  }

  return (
    // 모달 오버레이
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60"
      role="dialog"
      aria-modal="true"
      aria-label="자연어로 전략 입력"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
    >
      <div className="w-full max-w-md rounded-xl border border-gray-700 bg-gray-900 p-6 shadow-2xl">
        {/* 헤더 */}
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-white">자연어로 전략 입력</h2>
          <button
            className="rounded p-1 text-gray-500 hover:text-gray-300 focus:outline-none"
            onClick={onClose}
            aria-label="모달 닫기"
          >
            ×
          </button>
        </div>

        {/* 안내 */}
        <p className="mb-3 text-sm text-gray-400">
          한국어로 매매 조건을 입력하면 DSL로 변환합니다.
          <br />
          <span className="text-xs text-gray-500">
            예: "RSI 30 이하면 매수, RSI 70 이상이면 매도"
          </span>
        </p>

        {/* 입력 */}
        <textarea
          ref={textareaRef}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            setFeedback(null)
          }}
          rows={4}
          className="w-full resize-none rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="RSI 30 이하이고 거래량이 20일 평균의 1.5배 이상이면 매수"
          onKeyDown={(e) => {
            // ESC 닫기는 useModalBehavior 공용 훅이 처리 (IME 조합 중 무시 일관)
            if (e.ctrlKey && e.key === 'Enter') handleTranslate()
          }}
        />

        {/* 피드백 */}
        {feedback && (
          <div
            className={[
              'mt-2 rounded px-3 py-2 text-sm',
              feedbackType === 'success'
                ? 'bg-emerald-900/30 text-emerald-300'
                : feedbackType === 'warn'
                  ? 'bg-yellow-900/30 text-yellow-300'
                  : 'bg-red-900/30 text-red-400',
            ].join(' ')}
            role="alert"
          >
            {feedback}
          </div>
        )}

        {/* AI 면책 */}
        <p className="mt-3 text-xs text-gray-600">
          AI 변환은 보조 기능입니다. 매매 결정은 사용자 확인 후 진행하세요.
        </p>

        {/* 버튼 */}
        <div className="mt-4 flex justify-end gap-2">
          <button
            className="rounded-lg border border-gray-700 px-4 py-2 text-sm text-gray-400 hover:text-gray-200 focus:outline-none"
            onClick={onClose}
          >
            취소
          </button>
          <button
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus:ring-2 focus:ring-blue-400"
            onClick={handleTranslate}
            disabled={isPending || !text.trim()}
          >
            {isPending ? '변환 중...' : '변환 (Ctrl+Enter)'}
          </button>
        </div>
      </div>
    </div>
  )
}
