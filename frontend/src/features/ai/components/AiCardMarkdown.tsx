/**
 * AiCardMarkdown — AI 응답 본문 markdown light renderer (T-AI R2 §2-2)
 *
 * 외부 의존(react-markdown 등) 회피 — 오프라인 번들 우선 정책 (헌법 frontend §작업 원칙 정합).
 * 지원 marker (간단):
 *   - 빈 줄 → 단락 분리
 *   - `## 헤더` → h3 (h2 이상은 사용 안 함)
 *   - `**bold**` → strong
 *   - `- item` → bullet list
 *   - `[text](url)` → 인라인 링크 (target=_blank rel=noopener)
 *   - 그 외 마크다운은 plain text로 표시 (graceful — AI 출력 변형에 robust).
 *
 * 매매 결정권 0 — 본 렌더러는 표시 only.
 */
import { Fragment } from 'react'

interface Props {
  content: string
}

const INLINE_BOLD_RE = /\*\*([^*]+)\*\*/g
const INLINE_LINK_RE = /\[([^\]]+)\]\(([^)]+)\)/g

/** 한 줄 내 inline marker(bold·link) 처리 → ReactNode list. */
function renderInline(line: string): React.ReactNode[] {
  // 두 패턴 동시 처리 — 순차 split + 토큰화.
  const nodes: React.ReactNode[] = []
  let cursor = 0

  // 1차: link 우선 처리 (text 영역 안 bold 처리 위해 재귀 호출).
  const links = Array.from(line.matchAll(INLINE_LINK_RE))
  for (const m of links) {
    const start = m.index ?? 0
    if (start > cursor) {
      nodes.push(...renderBoldSegments(line.slice(cursor, start)))
    }
    const text = m[1]
    const url = m[2]
    // 외부 링크 — 안전 속성 + target=_blank.
    nodes.push(
      <a
        key={`lnk-${start}`}
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        className="text-blue-400 underline hover:text-blue-300"
      >
        {text}
      </a>,
    )
    cursor = start + m[0].length
  }
  if (cursor < line.length) {
    nodes.push(...renderBoldSegments(line.slice(cursor)))
  }

  return nodes
}

function renderBoldSegments(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  let cursor = 0
  const bolds = Array.from(text.matchAll(INLINE_BOLD_RE))
  for (const m of bolds) {
    const start = m.index ?? 0
    if (start > cursor) nodes.push(text.slice(cursor, start))
    nodes.push(
      <strong key={`b-${start}`} className="font-semibold text-white">
        {m[1]}
      </strong>,
    )
    cursor = start + m[0].length
  }
  if (cursor < text.length) nodes.push(text.slice(cursor))
  return nodes
}

export function AiCardMarkdown({ content }: Props) {
  // 빈 줄 단위 단락 분리.
  const blocks = content.split(/\n\n+/).map((b) => b.trim()).filter((b) => b.length > 0)

  return (
    <div className="space-y-3 text-sm leading-relaxed text-gray-200">
      {blocks.map((block, blockIdx) => {
        // 헤더 (## prefix).
        if (block.startsWith('## ')) {
          return (
            <h3 key={blockIdx} className="text-sm font-bold text-white">
              {renderInline(block.slice(3).trim())}
            </h3>
          )
        }
        // 불릿 리스트 (각 줄 - prefix).
        const lines = block.split('\n')
        const allBullets = lines.every((l) => l.trim().startsWith('- '))
        if (allBullets && lines.length > 0) {
          return (
            <ul key={blockIdx} className="list-disc space-y-1 pl-5">
              {lines.map((l, idx) => (
                <li key={idx}>{renderInline(l.trim().slice(2))}</li>
              ))}
            </ul>
          )
        }
        // 일반 단락 — 줄 바꿈을 <br/>로.
        return (
          <p key={blockIdx}>
            {lines.map((l, idx) => (
              <Fragment key={idx}>
                {renderInline(l)}
                {idx < lines.length - 1 && <br />}
              </Fragment>
            ))}
          </p>
        )
      })}
    </div>
  )
}
