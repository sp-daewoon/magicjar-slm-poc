/**
 * NewsItem — 뉴스 1행 컴포넌트
 *
 * ADR 0075 §5-3 NewsItem DTO 1:1 정합:
 *   title · bodySummary · publishedAt · url (외부 링크 새 탭)
 *
 * STOMP 0 LOCK (§6): import 없음.
 * AI provider import 0 (§11-2): sentiment NULL render only.
 */
import type { NewsItem as NewsItemDto } from '../api/newsApi'

interface Props {
  item: NewsItemDto
}

/** RFC 8601 문자열 → KST 날짜·시간 표시 */
function formatPublishedAt(isoStr: string): string {
  try {
    const d = new Date(isoStr)
    return d.toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return isoStr
  }
}

export function NewsItemRow({ item }: Props) {
  return (
    <li className="flex flex-col gap-1 border-b border-gray-800 py-3 last:border-b-0">
      {/* 제목 + 외부 링크 */}
      <a
        href={item.url}
        target="_blank"
        rel="noopener noreferrer"
        className="text-sm font-medium text-blue-400 hover:text-blue-300 hover:underline leading-snug"
      >
        {item.title}
      </a>

      {/* 요약 */}
      {item.bodySummary && (
        <p className="text-xs text-gray-400 leading-relaxed line-clamp-2">{item.bodySummary}</p>
      )}

      {/* 출처 + 발행시간 */}
      <div className="flex items-center gap-2 text-xs text-gray-500">
        <span className="rounded bg-gray-800 px-1.5 py-0.5 text-gray-400">{item.source}</span>
        <span>{formatPublishedAt(item.publishedAt)}</span>
      </div>
    </li>
  )
}
