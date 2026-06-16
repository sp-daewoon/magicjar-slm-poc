/**
 * RealtimeSlotGauge — KIS WS 구독 슬롯 게이지 (② 섹션)
 *
 * KIS OpenAPI 동시 WS 구독 슬롯 상한(max) 대비 사용량(used) 가시화.
 *  - 전체 게이지 바 + used/max 텍스트
 *  - 사용률 임계: < 70% 녹색, 70~90% amber, >= 90% 빨강 (슬롯 고갈 임박 경고)
 *  - trId 별 분해 (byTrId) — 어떤 거래 ID가 슬롯을 점유하는지
 *    · trId 코드 + 한국어 라벨 병기 (체결가 · H0STCNT0)
 *    · 클릭 시 점유 채널(종목) 목록 drill-down — "15채널이 무엇인지" 눈으로 검증
 *
 * 슬롯 고갈은 신규 종목 상세/전략 후보 구독 실패의 직접 원인 → 운영자 핵심 지표.
 */
import { useState } from 'react'
import { Link } from 'react-router-dom'
import type { RealtimeSlots } from '../../api/adminRealtime'
import { trIdHasLabel, trIdLabel } from '../../constants/kisTrId'

interface Props {
  slots: RealtimeSlots
}

/** 사용률 → 게이지 색상 + 한국어 힌트 */
function gaugeTone(ratio: number): { bar: string; text: string; hint: string | null } {
  if (ratio >= 0.9) {
    return {
      bar: 'bg-red-500',
      text: 'text-red-300',
      hint: '슬롯 고갈 임박 — 신규 구독(종목 상세·전략 후보) 실패 위험. 불필요 구독 정리 필요.',
    }
  }
  if (ratio >= 0.7) {
    return { bar: 'bg-amber-500', text: 'text-amber-300', hint: '슬롯 사용률 높음 — 추이 주시.' }
  }
  return { bar: 'bg-emerald-500', text: 'text-emerald-300', hint: null }
}

export function RealtimeSlotGauge({ slots }: Props) {
  const { used, max, byTrId, channelsByTrId } = slots
  const ratio = max > 0 ? used / max : 0
  const pct = Math.min(100, Math.round(ratio * 100))
  const tone = gaugeTone(ratio)

  // byTrId 내림차순 (점유 큰 trId 먼저)
  const trIdEntries = Object.entries(byTrId ?? {}).sort(([, a], [, b]) => b - a)

  // 펼침 상태 (trId → boolean)
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const toggle = (trId: string) =>
    setExpanded((prev) => ({ ...prev, [trId]: !prev[trId] }))

  return (
    <section
      aria-label="구독 슬롯 사용량"
      className="rounded-xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-end justify-between gap-2">
        <h2 className="text-sm font-semibold text-gray-200">구독 슬롯</h2>
        <p className={['text-sm font-bold tabular-nums', tone.text].join(' ')}>
          {used.toLocaleString('ko-KR')}
          <span className="text-gray-500"> / {max.toLocaleString('ko-KR')}</span>
          <span className="ml-1 text-xs text-gray-400">({pct}%)</span>
        </p>
      </div>

      {/* 게이지 바 */}
      <div
        className="h-2.5 w-full overflow-hidden rounded-full bg-gray-800"
        role="progressbar"
        aria-valuenow={used}
        aria-valuemin={0}
        aria-valuemax={max}
        aria-label="슬롯 사용률"
      >
        <div
          className={['h-full rounded-full transition-all', tone.bar].join(' ')}
          style={{ width: `${pct}%` }}
        />
      </div>

      {tone.hint && (
        <p className="mt-2 rounded bg-gray-950/60 px-2 py-1.5 text-xs text-amber-300">
          {tone.hint}
        </p>
      )}

      {/* trId 별 분해 + drill-down */}
      <div className="mt-4">
        <p className="mb-2 text-xs font-medium uppercase tracking-wide text-gray-500">
          trId 별 점유 · 행 클릭 시 종목 목록 펼침
        </p>
        {trIdEntries.length === 0 ? (
          <p className="text-xs text-gray-500">점유 중인 trId가 없습니다.</p>
        ) : (
          <div className="space-y-1.5">
            {trIdEntries.map(([trId, count]) => {
              const channels = channelsByTrId?.[trId]
              const hasDrill = channelsByTrId != null // 신버전 스냅샷 여부
              const isOpen = !!expanded[trId]
              const hasTrIdLabel = trIdHasLabel(trId)
              return (
                <div
                  key={trId}
                  className="overflow-hidden rounded-md border border-gray-700 bg-gray-950/60"
                >
                  <button
                    type="button"
                    onClick={() => toggle(trId)}
                    aria-expanded={isOpen}
                    className="flex w-full items-center justify-between gap-2 px-2.5 py-1.5 text-left text-xs transition-colors hover:bg-gray-900/60 focus:outline-none focus:ring-2 focus:ring-blue-400"
                  >
                    <span className="flex items-center gap-1.5">
                      <span
                        className={[
                          'text-gray-500 transition-transform',
                          isOpen ? 'rotate-90' : '',
                        ].join(' ')}
                        aria-hidden
                      >
                        ▶
                      </span>
                      <span className="font-medium text-gray-200">{trIdLabel(trId)}</span>
                      {hasTrIdLabel && (
                        <span className="font-mono text-[11px] text-gray-500">{trId}</span>
                      )}
                    </span>
                    <span className="rounded bg-gray-800 px-1.5 font-semibold text-gray-200 tabular-nums">
                      {count.toLocaleString('ko-KR')}채널
                    </span>
                  </button>

                  {isOpen && (
                    <div className="border-t border-gray-800 px-2.5 py-2">
                      {!hasDrill ? (
                        <p className="text-[11px] text-gray-500">
                          상세 정보 없음 (구버전 스냅샷 — 채널 목록 미제공)
                        </p>
                      ) : !channels || channels.length === 0 ? (
                        <p className="text-[11px] text-gray-500">점유 채널 데이터가 없습니다.</p>
                      ) : (
                        <>
                          <p className="mb-1.5 text-[11px] text-gray-500">
                            {trIdLabel(trId)} — {channels.length.toLocaleString('ko-KR')}채널
                            {channels.length !== count && (
                              <span className="ml-1 text-amber-400">
                                (집계 {count.toLocaleString('ko-KR')}와 불일치)
                              </span>
                            )}
                          </p>
                          <ul className="grid grid-cols-1 gap-x-4 gap-y-0.5 sm:grid-cols-2 lg:grid-cols-3">
                            {channels.map((ch) => (
                              <li
                                key={ch.code}
                                className="flex items-baseline gap-1.5 text-[11px]"
                              >
                                <Link
                                  to={`/symbol/${ch.code}`}
                                  className="font-mono text-blue-400 hover:text-blue-300 hover:underline"
                                >
                                  {ch.code}
                                </Link>
                                {ch.codeName && (
                                  <span className="truncate text-gray-300">{ch.codeName}</span>
                                )}
                              </li>
                            ))}
                          </ul>
                        </>
                      )}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        )}
      </div>
    </section>
  )
}
