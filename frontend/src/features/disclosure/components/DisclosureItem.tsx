/**
 * DisclosureItem — 공시 1행 컴포넌트
 *
 * ADR 0075 §5-3 DisclosureItem DTO 1:1 정합:
 *   reportNm · corpName · flrNm · rceptDt · url (DART 링크 새 탭)
 *   isCorrection=true → 정정공시 빨간 배지 표시
 *
 * STOMP 0 LOCK (§6): import 없음.
 * AI provider import 0 (§11-2).
 */
import type { DisclosureItem as DisclosureItemDto } from '../api/disclosureApi'

interface Props {
  item: DisclosureItemDto
}

/** YYYYMMDD raw → YYYY-MM-DD 표시 */
function formatRceptDt(raw: string): string {
  if (raw.length === 8) {
    return `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`
  }
  return raw
}

export function DisclosureItemRow({ item }: Props) {
  return (
    <li className="flex flex-col gap-1 border-b border-gray-800 py-3 last:border-b-0">
      {/* 보고서명 + 정정공시 배지 + DART 링크 */}
      <div className="flex items-start gap-2">
        {item.isCorrection && (
          <span
            aria-label="정정공시"
            className="mt-0.5 shrink-0 rounded bg-red-500 px-1.5 py-0.5 text-xs font-semibold text-white"
          >
            정정공시
          </span>
        )}
        <a
          href={item.url}
          target="_blank"
          rel="noopener noreferrer"
          className="text-sm font-medium text-blue-400 hover:text-blue-300 hover:underline leading-snug"
        >
          {item.reportNm}
        </a>
      </div>

      {/* 법인명 + 제출인 + 접수일 */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-gray-500">
        <span className="text-gray-400">{item.corpName}</span>
        {item.flrNm && <span>제출: {item.flrNm}</span>}
        {item.rm && <span className="text-gray-600">({item.rm})</span>}
        <span>{formatRceptDt(item.rceptDt)}</span>
      </div>
    </li>
  )
}
