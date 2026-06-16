/**
 * JournalNoteForm — 매매일지 메모 작성 폼
 *
 * ADR 0072 §6-3 정합 (POST /api/journal/notes)
 * textarea + 제출 버튼. 최대 4000자
 */
import { useState } from 'react'
import type { FormEvent } from 'react'

interface JournalNoteFormProps {
  onSubmit: (noteText: string) => void
  isSubmitting: boolean
}

export function JournalNoteForm({ onSubmit, isSubmitting }: JournalNoteFormProps) {
  const [text, setText] = useState('')

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    const trimmed = text.trim()
    if (!trimmed) return
    onSubmit(trimmed)
    setText('')
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="매매 회고를 자유롭게 입력하세요..."
        maxLength={4000}
        rows={3}
        className="w-full resize-none rounded-md border border-gray-700 bg-gray-900 px-3 py-2 text-sm text-gray-200 placeholder:text-gray-500 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
      />
      <div className="flex items-center justify-between">
        <span className="text-xs text-gray-600">{text.length} / 4000</span>
        <button
          type="submit"
          disabled={isSubmitting || !text.trim()}
          className="rounded-md bg-blue-600 px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 disabled:opacity-40"
        >
          {isSubmitting ? '저장 중...' : '메모 저장'}
        </button>
      </div>
    </form>
  )
}
