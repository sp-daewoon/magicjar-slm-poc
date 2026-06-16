/**
 * RealtimeCandleFreshnessPanel — 캔들 신선도 (④ 섹션)
 *
 * 종목·interval 별 마지막 캔들 갱신 경과 시간(ageSeconds) 표시.
 *  - ageSeconds > 180 (3분) → 경고색 (시세 유입 정지 의심)
 *  - 그 외 → 정상색
 *
 * 판독 가이드는 제목 옆 (!) 아이콘 hover/focus 시 말풍선(tooltip)으로 상시 노출.
 * (장 마감/동시호가 시간대에는 정상적으로 캔들이 멈추므로 오탐 방지 안내.)
 *
 * 경과 시간은 사람이 읽기 쉬운 한국어 포맷(초/분/시/일)으로 변환.
 */
import { Link } from 'react-router-dom'
import type { RealtimeCandleFreshness } from '../../api/adminRealtime'

interface Props {
  items: RealtimeCandleFreshness[]
}

/** 신선도 임계 — 초과 시 경고 (사용자 결재 LOCK) */
const STALE_THRESHOLD_SECONDS = 180

/** 패널 판독 가이드 문구 — (!) 아이콘 tooltip 내용 */
const FRESHNESS_GUIDE =
  'ageSeconds 가 3분(180초)을 넘으면 시세 유입 정지 의심으로 경고색 표시됩니다. ' +
  '단 15:20 이후 마감 동시호가·장 마감 시간대에는 캔들이 정상적으로 멈추므로 오탐입니다.'

/**
 * ageSeconds → 한국어 경과 포맷.
 *  - "39초 전" / "3분 20초 전" / "16시간 16분 39초 전" / "1시간 전"
 *  - 24시간(86400초) 초과 시 일·시 단위로 축약("2일 6시간 전", 분/초 생략)
 */
function formatAge(seconds: number): string {
  if (seconds < 0) return '-'
  if (seconds < 60) return `${seconds}초 전`

  // 매우 오래된 경우(>24h): 가독성 우선으로 일·시까지만
  if (seconds >= 86400) {
    const d = Math.floor(seconds / 86400)
    const h = Math.floor((seconds % 86400) / 3600)
    return h === 0 ? `${d}일 전` : `${d}일 ${h}시간 전`
  }

  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const parts: string[] = []
  if (h > 0) parts.push(`${h}시간`)
  if (m > 0) parts.push(`${m}분`)
  if (s > 0) parts.push(`${s}초`)
  return `${parts.join(' ')} 전`
}

export function RealtimeCandleFreshnessPanel({ items }: Props) {
  const staleItems = items.filter((it) => it.ageSeconds > STALE_THRESHOLD_SECONDS)
  const hasStale = staleItems.length > 0

  return (
    <section
      aria-label="캔들 신선도"
      className="rounded-xl border border-gray-800 bg-gray-900/40 p-4"
    >
      <div className="mb-3 flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <h2 className="text-sm font-semibold text-gray-200">캔들 신선도</h2>
          {/* 판독 가이드 — hover/focus 시 말풍선(tooltip) 노출 (상시 접근 가능) */}
          <span className="group relative inline-flex">
            <button
              type="button"
              tabIndex={0}
              aria-label={`캔들 신선도 판독 가이드: ${FRESHNESS_GUIDE}`}
              title={FRESHNESS_GUIDE}
              className={[
                'inline-flex h-4 w-4 items-center justify-center rounded-full border text-[10px] font-bold leading-none focus:outline-none focus:ring-1 focus:ring-blue-400',
                hasStale
                  ? 'border-amber-500/70 text-amber-300'
                  : 'border-gray-600 text-gray-400 hover:text-gray-200',
              ].join(' ')}
            >
              !
            </button>
            <span
              role="tooltip"
              className="pointer-events-none absolute left-0 top-5 z-10 hidden w-64 rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-[11px] leading-relaxed text-gray-300 shadow-lg group-hover:block group-focus-within:block"
            >
              {FRESHNESS_GUIDE}
            </span>
          </span>
        </div>
        <span className="text-xs text-gray-400">
          {items.length.toLocaleString('ko-KR')}건
          {hasStale && (
            <span className="ml-1 font-semibold text-amber-300">
              · 정체 {staleItems.length}건
            </span>
          )}
        </span>
      </div>

      {items.length === 0 ? (
        <p className="rounded-lg border border-gray-800 bg-gray-950/40 p-8 text-center text-sm text-gray-500">
          캔들 신선도 데이터가 없습니다.
        </p>
      ) : (
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((it) => {
            const isStale = it.ageSeconds > STALE_THRESHOLD_SECONDS
            return (
              <div
                key={`${it.code}:${it.interval}`}
                className={[
                  'flex items-center justify-between gap-2 rounded-lg border px-3 py-2',
                  isStale
                    ? 'border-amber-700/70 bg-amber-950/20'
                    : 'border-gray-800 bg-gray-950/40',
                ].join(' ')}
                data-stale={isStale}
              >
                <div className="min-w-0">
                  <Link
                    to={`/symbol/${it.code}`}
                    className="inline-flex max-w-full items-baseline gap-1.5 hover:underline"
                    title={`${it.codeName ?? it.code} 상세 보기`}
                  >
                    {it.codeName && (
                      <span className="truncate text-xs text-gray-100">{it.codeName}</span>
                    )}
                    <span className="font-mono text-xs text-blue-400">{it.code}</span>
                  </Link>
                  <span className="ml-1.5 rounded bg-gray-800 px-1.5 py-0.5 text-[11px] text-gray-400">
                    {it.interval}
                  </span>
                </div>
                <span
                  className={[
                    'shrink-0 text-xs font-medium tabular-nums',
                    isStale ? 'text-amber-300' : 'text-emerald-300',
                  ].join(' ')}
                >
                  {formatAge(it.ageSeconds)}
                </span>
              </div>
            )
          })}
        </div>
      )}
    </section>
  )
}
