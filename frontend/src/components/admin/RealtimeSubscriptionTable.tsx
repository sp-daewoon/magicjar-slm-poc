/**
 * RealtimeSubscriptionTable — 실시간 구독 테이블 (③ 섹션)
 *
 * 현재 KIS WS 가 구독 중인 모든 항목 표시:
 *  - trId (거래 ID + 한국어 라벨) · 종목(코드+명, 상세 링크) · owner(인간화 배지) · since
 *
 * owner 인간화 (CLAUDE.md 방향성 가드 정합 — owner 별 TTL·우선순위 분리):
 *  - strategy:86  → "전략 #86"     (보라)
 *  - detail:xxxx  → "종목 상세"     (파랑)
 *  - holding:*    → "보유 감시"     (초록)
 *  - pending_buy:*→ "매수 대기"     (주황)
 *  - screener     → "스크리너 후보"  (amber)
 * 롤링 배포 중 legacy 이중 prefix(strategy:strategy:86)는 collapse 후 표시.
 *
 * 정렬: code 우선 → trId 차순 (같은 종목 체결가+호가 2행이 인접하게 묶임 — 중복 오해 방지).
 */
import { Link } from 'react-router-dom'
import type { RealtimeSubscription } from '../../api/adminRealtime'
import { trIdHasLabel, trIdLabel } from '../../constants/kisTrId'
import { ownerView, OWNER_BADGE_CLASS, OWNER_LEGEND } from '../../constants/realtimeOwner'

interface Props {
  subscriptions: RealtimeSubscription[]
}

function OwnerBadge({ owner }: { owner: string }) {
  const view = ownerView(owner)
  const cls = OWNER_BADGE_CLASS[view.tone]
  return (
    <span
      title={view.title}
      className={['inline-flex rounded border px-1.5 py-0.5 text-[11px] font-medium', cls].join(' ')}
    >
      {view.label}
    </span>
  )
}

function formatSince(since: string | null): string | null {
  if (!since) return null
  const date = new Date(since)
  if (Number.isNaN(date.getTime()) || date.getTime() <= 0) return null
  return date.toLocaleString('ko-KR')
}

/**
 * owner 종류 범례 (테이블 하단) — 라벨만 나열한다.
 *
 * 설명(desc)은 인라인으로 노출하지 않고, 라벨 배지에 마우스를 1초 이상 올리면
 * 브라우저 native tooltip(`title`)으로 표시한다(최고 관리자 요청 2026-06-13 —
 * "설명을 굳이 볼 필요 없으니 라벨만, hover 시에만 설명"). `cursor-help`로 hover 정보 존재를 알린다.
 */
function OwnerLegend() {
  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[11px] text-gray-500">
      <span className="font-medium text-gray-400">범례:</span>
      {OWNER_LEGEND.map((entry) => (
        <span
          key={entry.tone}
          title={entry.desc}
          className={[
            'inline-flex cursor-help rounded border px-1.5 py-0.5 font-medium',
            OWNER_BADGE_CLASS[entry.tone],
          ].join(' ')}
        >
          {entry.label}
        </span>
      ))}
    </div>
  )
}

export function RealtimeSubscriptionTable({ subscriptions }: Props) {
  const count = subscriptions.length

  // code 우선 → trId 차순 정렬 (같은 종목 2행 인접 — 중복 오해 방지)
  const sorted = [...subscriptions].sort((a, b) => {
    if (a.code !== b.code) return a.code.localeCompare(b.code)
    return a.trId.localeCompare(b.trId)
  })

  return (
    <section
      aria-label="실시간 구독 목록"
      className="rounded-xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-200">구독 종목</h2>
        <span className="text-xs text-gray-400">
          총 <span className="font-semibold text-white">{count.toLocaleString('ko-KR')}</span>건
        </span>
      </div>

      {count === 0 ? (
        <p className="rounded-lg border border-gray-800 bg-gray-950/40 p-8 text-center text-sm text-gray-500">
          구독 중인 종목이 없습니다.
        </p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border border-gray-800">
            <table className="w-full border-collapse text-sm">
              <thead className="bg-gray-900 text-xs uppercase tracking-wide text-gray-400">
                <tr>
                  <th className="border-b border-gray-800 p-3 text-left font-medium">구분(trId)</th>
                  <th className="border-b border-gray-800 p-3 text-left font-medium">종목</th>
                  <th className="border-b border-gray-800 p-3 text-left font-medium">Owner</th>
                  <th className="border-b border-gray-800 p-3 text-left font-medium">구독 시작</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((sub) => {
                  const hasTrIdLabel = trIdHasLabel(sub.trId)
                  const sinceLabel = formatSince(sub.since)
                  return (
                    <tr
                      key={`${sub.trId}:${sub.code}`}
                      className="border-b border-gray-800 transition-colors hover:bg-gray-900/60"
                    >
                      <td className="p-3">
                        <span className="text-gray-200">{trIdLabel(sub.trId)}</span>
                        {hasTrIdLabel && (
                          <span className="ml-1.5 font-mono text-[11px] text-gray-500">
                            {sub.trId}
                          </span>
                        )}
                      </td>
                      <td className="p-3">
                        <Link
                          to={`/symbol/${sub.code}`}
                          className="text-sm text-gray-100 hover:text-blue-300 hover:underline"
                        >
                          {sub.codeName}
                          <span className="ml-2 font-mono text-xs text-blue-400">{sub.code}</span>
                        </Link>
                      </td>
                      <td className="p-3">
                        {sub.owners.length === 0 ? (
                          <span className="text-xs italic text-gray-600">(owner 없음)</span>
                        ) : (
                          <div className="flex flex-wrap gap-1">
                            {sub.owners.map((owner) => (
                              <OwnerBadge key={owner} owner={owner} />
                            ))}
                          </div>
                        )}
                      </td>
                      <td className="p-3 text-xs text-gray-400">
                        {sinceLabel ?? (
                          <span
                            title="백엔드 스냅샷에 구독 시작 시각이 기록되지 않은 항목입니다."
                            className="text-gray-500"
                          >
                            구독 시각 미기록
                          </span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <OwnerLegend />
        </>
      )}
    </section>
  )
}
