/**
 * DiscoveryScorecardPage — 발굴 스코어카드 (forward-test hit-rate) 화면
 *
 * 라우트: /strategy/scorecard
 *
 * 백엔드는 V77 + DiscoveryScorecardScheduler(08:30 snapshot · 23:30 채점)로 매일
 * 발굴 TOP N을 기록·채점해 왔으나 화면이 0건이었다 (2026-06-10 strategy-mock-paper-flow
 * 보고서 발전점 #2). 본 화면은 그 데이터를 보는 유일한 창이다.
 *
 * 구성:
 *   1. 기간 프리셋(7/30/90일) + 프리셋 필터
 *   2. 전체 요약 + 프리셋별 요약 카드 (표본·채점·hit rate·평균 수익률)
 *   3. 상세 rows 테이블 (snapshot_date DESC · rank ASC, 미채점 = "미채점" 정직 표기)
 *
 * ### 가드 (방향성 보존)
 * - read-only — 자동매매 결정권 0, watchlist write 0, KIS 호출 0, LLM 호출 0.
 * - 미채점 row는 채점 필드 null → "미채점" 표기 (mock 합성 0 — feedback_no_mock_data).
 * - 빈 응답은 "데이터 없음 + 쌓이는 절차 안내"로 정직 표기.
 */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  discoveryScorecardApi,
  type DiscoveryScorecardRowDto,
  type DiscoveryScorecardSummaryDto,
} from '../../features/screener/api/discoveryScorecardApi'
import { DAYTRADE_PRESET_OPTIONS } from '../../features/screener/api/daytradeDiscoverApi'

type RangePreset = 7 | 30 | 90

const RANGE_OPTIONS: { value: RangePreset; label: string }[] = [
  { value: 7, label: '7일' },
  { value: 30, label: '30일' },
  { value: 90, label: '90일' },
]

/** KST 기준 yyyy-MM-dd */
function kstDateString(daysAgo: number): string {
  const now = new Date()
  const kst = new Date(now.getTime() + 9 * 60 * 60 * 1000)
  kst.setUTCDate(kst.getUTCDate() - daysAgo)
  return kst.toISOString().slice(0, 10)
}

/** BigDecimal(number|string) → 소수 2자리 % 표기. null이면 '—' */
function pct(value: number | string | null): string {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toFixed(2)}%`
}

function pctColor(value: number | string | null): string {
  if (value === null || value === undefined) return 'text-gray-500'
  const n = Number(value)
  if (!Number.isFinite(n)) return 'text-gray-500'
  if (n > 0) return 'text-emerald-400'
  if (n < 0) return 'text-red-400'
  return 'text-gray-400'
}

/** 가격 표기 (천 단위 콤마). null이면 '—' */
function won(value: number | string | null): string {
  if (value === null || value === undefined) return '—'
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  return n.toLocaleString('ko-KR')
}

/** presetKey → UI 라벨 (옵션에 없으면 key 그대로 정직 표기) */
function presetLabel(presetKey: string | null): string {
  if (presetKey === null) return '전체'
  return DAYTRADE_PRESET_OPTIONS.find((o) => o.key === presetKey)?.label ?? presetKey
}

/** 요약 카드 1장 (전체 또는 프리셋 단위) */
function SummaryCard({
  summary,
  emphasized,
}: {
  summary: DiscoveryScorecardSummaryDto
  emphasized?: boolean
}) {
  return (
    <div
      className={[
        'rounded-xl border px-4 py-3',
        emphasized
          ? 'border-indigo-800/60 bg-indigo-950/20'
          : 'border-gray-800 bg-gray-900/60',
      ].join(' ')}
    >
      <p className="text-xs font-medium text-gray-400">
        {summary.presetKey === null ? '전체 (모든 프리셋)' : presetLabel(summary.presetKey)}
      </p>
      {summary.presetKey !== null && (
        <p className="mt-0.5 font-mono text-[10px] text-gray-600">{summary.presetKey}</p>
      )}
      <div className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1.5 text-sm sm:grid-cols-4">
        <div>
          <p className="text-[11px] text-gray-500">hit rate</p>
          <p className={`tabular-nums font-semibold ${pctColor(summary.hitRatePct)}`}>
            {summary.hitRatePct === null ? '—' : `${Number(summary.hitRatePct).toFixed(1)}%`}
          </p>
        </div>
        <div>
          <p className="text-[11px] text-gray-500">평균 다음날 종가</p>
          <p className={`tabular-nums font-semibold ${pctColor(summary.avgNextReturnPct)}`}>
            {pct(summary.avgNextReturnPct)}
          </p>
        </div>
        <div>
          <p className="text-[11px] text-gray-500">평균 다음날 시초가</p>
          <p className={`tabular-nums ${pctColor(summary.avgNextOpenReturnPct)}`}>
            {pct(summary.avgNextOpenReturnPct)}
          </p>
        </div>
        <div>
          <p className="text-[11px] text-gray-500">표본 (채점/기록)</p>
          <p className="tabular-nums text-gray-300">
            {summary.scoredCount.toLocaleString()}/{summary.sampleCount.toLocaleString()}
          </p>
        </div>
      </div>
    </div>
  )
}

/** 상세 row */
function ScorecardRow({ row }: { row: DiscoveryScorecardRowDto }) {
  const scored = row.scoredAt !== null
  return (
    <tr className="border-t border-gray-800/50">
      <td className="whitespace-nowrap py-2 pr-3 font-mono text-xs text-gray-400">
        {row.snapshotDate}
      </td>
      <td className="py-2 pr-3 text-xs text-gray-400" title={row.presetKey}>
        {presetLabel(row.presetKey)}
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-500">{row.rank}</td>
      <td className="py-2 pr-3">
        <Link
          to={`/symbol/${row.instrumentCode}`}
          className="text-sm text-gray-200 hover:text-white hover:underline"
        >
          {row.instrumentName}
        </Link>
        <span className="ml-1.5 font-mono text-[10px] text-gray-600">{row.instrumentCode}</span>
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-400">
        {Number(row.score).toFixed(3)}
      </td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-500">{won(row.baseClose)}</td>
      <td className="py-2 pr-3 text-right tabular-nums text-gray-500">{won(row.nextClose)}</td>
      <td className={`py-2 pr-3 text-right tabular-nums ${pctColor(row.nextReturnPct)}`}>
        {pct(row.nextReturnPct)}
      </td>
      <td className={`py-2 pr-3 text-right tabular-nums ${pctColor(row.nextOpenReturnPct)}`}>
        {pct(row.nextOpenReturnPct)}
      </td>
      <td className="py-2 text-right">
        {scored ? (
          <span className="rounded-full bg-emerald-900/40 px-2 py-0.5 text-[10px] text-emerald-300">
            채점됨
          </span>
        ) : (
          <span
            className="rounded-full bg-gray-800 px-2 py-0.5 text-[10px] text-gray-400"
            title="다음 거래일 일봉 채점 대기 (mock 합성 0)"
          >
            미채점
          </span>
        )}
      </td>
    </tr>
  )
}

export function DiscoveryScorecardPage() {
  const [rangeDays, setRangeDays] = useState<RangePreset>(30)
  const [presetKey, setPresetKey] = useState<string | null>(null)

  const from = useMemo(() => kstDateString(rangeDays - 1), [rangeDays])

  const { data, isLoading, error } = useQuery({
    queryKey: ['discovery-scorecard', from, presetKey],
    queryFn: () => discoveryScorecardApi.get({ from, presetKey }),
    staleTime: 60_000,
  })

  const rows = data?.rows ?? []

  return (
    <div className="mx-auto max-w-5xl px-4 py-6">
      <header className="mb-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h1 className="text-xl font-bold text-white">발굴 스코어카드</h1>
          <Link
            to="/strategy/daytrade-discover"
            className="rounded-lg border border-gray-700 px-3 py-1.5 text-xs text-gray-400 hover:bg-gray-800"
          >
            단타 발굴 화면 →
          </Link>
        </div>
        <p className="mt-1 text-sm text-gray-500">
          매일 장전(08:30) 발굴 TOP N을 기록하고 다음 거래일 실제 일봉으로 자동 채점한{' '}
          <span className="text-gray-400">forward-test 증거</span>입니다. 백테스트(과거)와 달리
          앞으로의 실제 적중을 측정합니다. 관찰·기록 전용 — 매매 결정권 0.
        </p>
      </header>

      {/* 필터 */}
      <section className="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-gray-800 bg-gray-900/40 px-4 py-3">
        <div className="flex items-center gap-2">
          <span className="text-xs text-gray-500">기간</span>
          <div role="group" aria-label="조회 기간" className="flex items-center rounded-lg bg-gray-900 p-1">
            {RANGE_OPTIONS.map((opt) => (
              <button
                key={opt.value}
                type="button"
                aria-pressed={rangeDays === opt.value}
                onClick={() => setRangeDays(opt.value)}
                className={[
                  'rounded-md px-3 py-1 text-xs font-medium transition-colors',
                  rangeDays === opt.value
                    ? 'bg-indigo-600 text-white'
                    : 'text-gray-400 hover:text-gray-200',
                ].join(' ')}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-gray-500">프리셋</span>
          <select
            value={presetKey ?? ''}
            onChange={(e) => setPresetKey(e.target.value || null)}
            aria-label="프리셋 필터"
            className="rounded-lg border border-gray-700 bg-gray-800 px-3 py-1.5 text-sm text-white focus:border-indigo-500 focus:outline-none"
          >
            {DAYTRADE_PRESET_OPTIONS.map((o) => (
              <option key={o.key ?? '__all__'} value={o.key ?? ''}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </section>

      {/* 로딩 */}
      {isLoading && (
        <div className="rounded-xl border border-gray-800 bg-gray-900/40 py-12 text-center text-sm text-gray-500">
          스코어카드를 불러오는 중...
        </div>
      )}

      {/* 에러 */}
      {error && !isLoading && (
        <div className="rounded-xl border border-red-900/40 bg-red-950/20 py-10 text-center">
          <p className="text-sm text-red-400">스코어카드를 불러오지 못했습니다.</p>
          <p className="mt-1 text-xs text-gray-500">{(error as Error).message}</p>
        </div>
      )}

      {data && !isLoading && (
        <div className="space-y-6">
          {/* 요약 */}
          <section>
            <h2 className="mb-2 text-sm font-semibold text-gray-300">
              요약 <span className="font-normal text-gray-600">({data.from} ~ {data.to})</span>
            </h2>
            {data.overall.sampleCount === 0 ? (
              <div className="rounded-xl border border-dashed border-gray-800 px-4 py-10 text-center">
                <p className="text-sm text-gray-400">조회 기간에 기록된 발굴 스냅샷이 없습니다.</p>
                <p className="mt-2 text-xs text-gray-600">
                  거래일 아침 08:30(KST)에 발굴 TOP N이 자동 기록되고, 다음 거래일 23:30에
                  채점됩니다. 기록이 쌓이면 여기에 표시됩니다. (mock 합성 0)
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                <SummaryCard summary={data.overall} emphasized />
                {data.byPreset.length > 0 && (
                  <div className="grid gap-3 lg:grid-cols-2">
                    {data.byPreset.map((s) => (
                      <SummaryCard key={s.presetKey ?? '__all__'} summary={s} />
                    ))}
                  </div>
                )}
              </div>
            )}
          </section>

          {/* 상세 */}
          {rows.length > 0 && (
            <section>
              <h2 className="mb-2 text-sm font-semibold text-gray-300">
                상세{' '}
                <span className="font-normal text-gray-600">
                  ({rows.length.toLocaleString()}건 · 발굴일 최신순)
                </span>
              </h2>
              <div className="overflow-x-auto rounded-xl border border-gray-800 bg-gray-900/40 p-3">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-xs text-gray-500">
                      <th className="pb-2 pr-3 text-left font-medium">발굴일</th>
                      <th className="pb-2 pr-3 text-left font-medium">프리셋</th>
                      <th className="pb-2 pr-3 text-right font-medium">순위</th>
                      <th className="pb-2 pr-3 text-left font-medium">종목</th>
                      <th className="pb-2 pr-3 text-right font-medium">점수</th>
                      <th className="pb-2 pr-3 text-right font-medium">기준종가</th>
                      <th className="pb-2 pr-3 text-right font-medium">다음날종가</th>
                      <th className="pb-2 pr-3 text-right font-medium">종가수익률</th>
                      <th className="pb-2 pr-3 text-right font-medium">시초가수익률</th>
                      <th className="pb-2 text-right font-medium">채점</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <ScorecardRow
                        key={`${row.snapshotDate}-${row.presetKey}-${row.instrumentCode}`}
                        row={row}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  )
}
