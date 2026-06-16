/**
 * MarketTrendPage — /market/trend 신규 페이지 (T-MARKET R2 §2-1)
 *
 * 3 패널 레이아웃:
 *   좌측: 지수 4 카드 토글 + LWC v4 추세 차트 (MA periods overlay)
 *   중앙: 추세 라벨 카드 + CrossSignal 강조 (GOLDEN·DEAD 점멸)
 *   우측: 섹터 ranking (todayTop 5 / longTop 5 탭)
 *
 * 우상단 인라인 토글: 프리셋 (PresetButtons reuse).
 * 프리셋 변경 시 PUT /api/users/me/preferences/trend → STOMP 다중 탭 sync.
 *
 * AI 진입점: "시장 추세 해석" AiExplainEntry (5번째 진입점).
 */
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  DEFAULT_INDEX_CODES,
  INDEX_LABELS,
  toNumberOrNull,
  type CrossSignal,
} from '../../../api/market'
import { useIndexCandles } from '../../../api/hooks/useIndexCandles'
import { useIndexTrend } from '../../../api/hooks/useIndexTrend'
import { useMarketIndex } from '../../../api/hooks/useMarketIndex'
import { useSectorRanking } from '../../../api/hooks/useSectorRanking'
import {
  detectPresetId,
  useTrendPreference,
  type TrendPreset,
} from '../../../api/hooks/useTrendPreference'
import { useOnboardingStore } from '../../../stores/onboarding'
import { AiExplainEntry } from '../../ai/components/AiExplainEntry'
import { CrossSignalBadge } from '../components/CrossSignalBadge'
import { IndexTrendCard } from '../components/IndexTrendCard'
import { IndexTrendChart } from '../components/IndexTrendChart'
import { PresetButtons } from '../../settings/components/PresetButtons'
import { SectorRankingPanel } from '../components/SectorRankingPanel'
import { TrendLabelBadge } from '../components/TrendLabelBadge'

export function MarketTrendPage() {
  const userId = useOnboardingStore((s) => s.registeredUserId)
  const [selectedCode, setSelectedCode] = useState<string>('0001') // default KOSPI

  // hooks
  const { snapshot, isLoading: isStripLoading, isError: isStripError } = useMarketIndex({
    enabled: userId != null,
  })
  const { trend, isLoading: isTrendLoading, isError: isTrendError } = useIndexTrend({
    code: selectedCode,
    enabled: userId != null,
  })
  const {
    candles: indexCandles,
    isLoading: isIndexCandlesLoading,
    isError: isIndexCandlesError,
  } = useIndexCandles({
    code: selectedCode,
    interval: '1d',
    limit: 260,
    enabled: userId != null,
  })
  const { ranking, isLoading: isRankingLoading, isError: isRankingError } = useSectorRanking({
    enabled: userId != null,
  })
  const { preference, update: updatePref, isUpdating } = useTrendPreference({
    userId,
    enabled: userId != null,
  })

  // 프리셋 변경 — 현재 prefence 기반 비교.
  const currentPresetId = useMemo(
    () => (preference ? detectPresetId(preference.maPeriods) : 'classic'),
    [preference],
  )

  function handlePresetChange(preset: TrendPreset | 'custom') {
    if (preset === 'custom') {
      // SettingsModal로 안내 — 인라인 직접 입력은 제공 안 함 (UX 단순).
      // 향후 inline edit 슬롯 확장 가능.
      return
    }
    if (!preference) return
    updatePref({
      maPeriods: preset.maPeriods,
      rsPeriod: preference.rsPeriod,
      crossPair: preference.crossPair,
    })
  }

  // setup 미완 — 안내.
  if (userId == null) {
    return (
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-6 px-4 py-24 text-center">
        <h1 className="text-2xl font-bold text-white">시장 추세 분석</h1>
        <p className="text-sm text-gray-400">
          KIS 계좌 등록을 완료하면 시장 지수·섹터 추세를 확인할 수 있습니다.
        </p>
        <Link
          to="/setup"
          className="rounded-lg bg-blue-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-400"
        >
          계좌 등록하러 가기
        </Link>
      </div>
    )
  }

  // AI MARKET 진입점 context — backend prompt_template `market_v1` variables_schema 정합.
  const aiContext = {
    kospiTrend: trend?.trendLabel ?? 'UNKNOWN',
    selectedIndex: INDEX_LABELS[selectedCode] ?? selectedCode,
    selectedIndexCode: selectedCode,
    maPeriods: preference?.maPeriods ?? [5, 20, 60],
    rsPeriod: preference?.rsPeriod ?? 60,
    crossSignal: (trend?.crossSignal as CrossSignal | undefined) ?? 'NEUTRAL',
    lastClose: toNumberOrNull(trend?.lastClose ?? null),
    changeRate: toNumberOrNull(trend?.changeRate ?? null),
    sectorTopList: ranking?.todayTop?.map((s) => s.sectorName).slice(0, 5) ?? [],
    sectorLongList: ranking?.longTop?.map((s) => s.sectorName).slice(0, 5) ?? [],
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* 페이지 헤더 + 프리셋 + AI 진입점 */}
      <div className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-gray-100">시장 추세 분석</h1>
          <p className="mt-0.5 text-sm text-gray-500">
            4 지수 + KRX 섹터 추세 — 사용자 MA periods 적용 (사용자 설정 변경 시 즉시 반영)
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          {isUpdating && (
            <span className="text-[10px] text-gray-500">프리셋 적용 중...</span>
          )}
          <PresetButtons selectedId={currentPresetId} onSelect={handlePresetChange} />
        </div>
      </div>

      {/* AI 진입점 — 5번째 (MARKET) */}
      <div className="mb-5">
        <AiExplainEntry
          type="MARKET"
          userId={userId}
          context={aiContext}
          disabled={!trend || trend.dataInsufficient}
          disabledMessage={
            !trend
              ? '지수 데이터 로딩 중...'
              : trend.dataInsufficient
                ? '캔들 데이터 수집 후 사용 가능합니다.'
                : undefined
          }
        />
      </div>

      {/* 3 패널 grid */}
      <div className="grid grid-cols-1 gap-5 lg:grid-cols-3">
        {/* 좌측 — 지수 토글 + 차트 */}
        <section className="lg:col-span-2 space-y-4">
          {/* 지수 선택 토글 (4 카드) */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {DEFAULT_INDEX_CODES.map((code) => {
              const row = snapshot?.indexes.find((r) => r.code === code)
              if (!row) {
                return (
                  <button
                    key={code}
                    type="button"
                    onClick={() => setSelectedCode(code)}
                    className={`min-w-[140px] rounded-xl border bg-gray-900/60 px-4 py-3 text-left transition-colors ${
                      selectedCode === code
                        ? 'border-blue-500 bg-blue-950/40'
                        : 'border-gray-800 hover:border-gray-600'
                    }`}
                    aria-pressed={selectedCode === code}
                  >
                    <span className="text-sm font-semibold text-gray-100">
                      {INDEX_LABELS[code]}
                    </span>
                    <p className="mt-1 text-xs text-gray-500">데이터 수집 중</p>
                  </button>
                )
              }
              return (
                <IndexTrendCard
                  key={code}
                  index={row}
                  onClick={setSelectedCode}
                  selected={selectedCode === code}
                />
              )
            })}
          </div>
          {(isStripLoading || isStripError) && (
            <p className="text-xs text-gray-500">
              {isStripError ? '지수 데이터를 불러오는 중 오류' : '지수 데이터 로딩 중...'}
            </p>
          )}

          {/* 차트 */}
          <div>
            <h2 className="mb-2 text-sm font-semibold text-gray-200">
              {INDEX_LABELS[selectedCode] ?? selectedCode} — 지수 일봉
            </h2>
            <IndexTrendChart
              trend={trend}
              candles={indexCandles?.candles ?? []}
              interval={indexCandles?.interval ?? '1d'}
              isLoading={isTrendLoading || isIndexCandlesLoading}
              isError={isTrendError || isIndexCandlesError}
            />
          </div>
        </section>

        {/* 우측 — 섹터 ranking */}
        <SectorRankingPanel
          ranking={ranking}
          isLoading={isRankingLoading}
          isError={isRankingError}
        />
      </div>

      {/* 중앙(하단) — 추세 라벨 + CrossSignal 강조 */}
      <section
        aria-labelledby="trend-summary-heading"
        className="mt-5 rounded-2xl border border-gray-800 bg-gray-900/40 p-4"
      >
        <h2 id="trend-summary-heading" className="mb-3 text-sm font-semibold text-gray-200">
          추세 요약 — {INDEX_LABELS[selectedCode] ?? selectedCode}
        </h2>
        {isTrendLoading ? (
          <div className="h-16 animate-pulse rounded-lg border border-gray-800 bg-gray-900" />
        ) : isTrendError ? (
          <div role="alert" className="text-xs text-red-400">
            추세 데이터를 불러오는 중 오류가 발생했습니다.
          </div>
        ) : !trend ? (
          <div role="status" className="text-xs text-gray-500">
            데이터 없음.
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">추세:</span>
              <TrendLabelBadge label={trend.trendLabel} />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">MA 교차:</span>
              <CrossSignalBadge signal={trend.crossSignal} pulse={true} />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-xs text-gray-500">periods:</span>
              <span className="text-xs text-gray-300 tabular-nums">
                {trend.periodsUsed.join(' · ')}일
              </span>
              {trend.isUserCustom && (
                <span className="rounded bg-blue-900/40 px-1.5 py-0.5 text-[10px] text-blue-300">
                  user pref
                </span>
              )}
            </div>
            {trend.dataInsufficient && (
              <span className="rounded bg-amber-900/40 px-2 py-0.5 text-[10px] text-amber-300">
                데이터 부족
              </span>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
