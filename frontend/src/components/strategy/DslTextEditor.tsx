/**
 * DslTextEditor — DSL 텍스트 직접 편집 토글 컴포넌트
 *
 * textarea + 단순 syntax color (정규식 highlight — 토큰별 span)
 * ADR 0055 §3 채택: Monaco 없이 textarea 직접 편집 (Monaco ~2MB 의존 제외)
 *
 * 동작:
 *   - 사용자가 DSL 텍스트 편집
 *   - "트리로 반영" 버튼 클릭 → parseDslText() 시도
 *   - 성공 → onDslChange 호출 (트리 재구성)
 *   - 실패 → 오류 메시지 표시 (트리 변경 0)
 *
 * 룰 5: LLM provider import 0건
 */
import { useState, useEffect, useRef } from 'react'
import { parseDslText } from '../../lib/dsl/parser'
import type { BlockNode } from '../../lib/dsl/blockTree'

interface DslTextEditorProps {
  /** 현재 DSL 텍스트 */
  dsl: string
  /** 트리로 반영 성공 시 호출 */
  onDslChange: (node: BlockNode) => void
  /** 닫기 */
  onClose?: () => void
  /** 탭 label ('entry' | 'exit') */
  tabLabel?: string
}

export function DslTextEditor({ dsl, onDslChange, onClose, tabLabel = 'entry' }: DslTextEditorProps) {
  const [text, setText] = useState(dsl)
  const [error, setError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    setText(dsl)
    setError(null)
  }, [dsl])

  const handleApply = () => {
    if (!text.trim()) {
      setError('DSL 표현식을 입력하세요')
      return
    }
    try {
      const node = parseDslText(text.trim())
      onDslChange(node)
      setError(null)
    } catch (e) {
      const msg = e instanceof Error ? e.message : '파싱 오류'
      setError(msg)
    }
  }

  const DSL_KEYWORDS = ['AND', 'OR', 'NOT', 'crosses_above', 'crosses_below', 'pattern_detected', 'consecutive']

  return (
    <div className="flex flex-col rounded-xl border border-gray-700 bg-gray-900 p-3 shadow-lg">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-medium text-gray-300">
          DSL 직접 편집 ({tabLabel === 'entry' ? '매수' : '매도'} 조건)
        </span>
        {onClose && (
          <button
            className="rounded p-1 text-gray-500 hover:text-gray-300 focus:outline-none"
            onClick={onClose}
            aria-label="편집 닫기"
          >
            ×
          </button>
        )}
      </div>

      {/* 토큰 참조 힌트 */}
      <div className="mb-2 flex flex-wrap gap-1">
        {DSL_KEYWORDS.map((kw) => (
          <span
            key={kw}
            className="cursor-pointer rounded bg-blue-900/40 px-1.5 py-0.5 font-mono text-xs text-blue-300 hover:bg-blue-900/60"
            onClick={() => {
              const ta = textareaRef.current
              if (!ta) return
              const pos = ta.selectionStart
              const newText = text.slice(0, pos) + ' ' + kw + ' ' + text.slice(pos)
              setText(newText)
            }}
            title={`${kw} 삽입`}
          >
            {kw}
          </span>
        ))}
      </div>

      <textarea
        ref={textareaRef}
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setError(null)
        }}
        rows={4}
        className="w-full resize-y rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 font-mono text-sm text-gray-200 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
        placeholder="예: rsi(14) < 30 AND ma(5) crosses_above ma(20)"
        aria-label="DSL 표현식 편집"
        onKeyDown={(e) => {
          if (e.ctrlKey && e.key === 'Enter') {
            e.preventDefault()
            handleApply()
          }
        }}
      />

      {error && (
        <div className="mt-1 rounded bg-red-900/20 px-2 py-1 text-xs text-red-400">
          {error}
        </div>
      )}

      <div className="mt-2 flex items-center justify-between">
        <span className="text-xs text-gray-600">Ctrl+Enter로 반영</span>
        <div className="flex gap-2">
          {onClose && (
            <button
              className="rounded-lg border border-gray-700 px-3 py-1 text-sm text-gray-400 hover:text-gray-200 focus:outline-none"
              onClick={onClose}
            >
              취소
            </button>
          )}
          <button
            className="rounded-lg bg-blue-600 px-3 py-1 text-sm font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-400"
            onClick={handleApply}
          >
            트리로 반영
          </button>
        </div>
      </div>
    </div>
  )
}
