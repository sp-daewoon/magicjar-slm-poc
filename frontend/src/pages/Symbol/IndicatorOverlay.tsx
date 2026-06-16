/**
 * IndicatorOverlay — LWC v4 차트 지표 오버레이
 *
 * 역할:
 * - 선택된 지표 ID마다 useIndicators hook을 호출하여 REST 시계열을 가져옴
 * - IChartApi 참조를 받아 addLineSeries / addHistogramSeries로 overlay 추가
 * - 지표 활성/비활성 토글 시 series remove
 *
 * multi-output 지표 처리 (spec §5-2 LOCK):
 *   macd → {macd(line), signal(line), histogram(histogram)}
 *   bb   → {upper, middle, lower} → 3 line series
 *   stochastic_d → {k, d} → 2 line series (별도 pane 권장)
 *   supertrend → {value(line), direction 무시}
 *   pivot → {p,r1,r2,r3,s1,s2,s3} → 7 line series
 *
 * LWC v4 API 제약 (ADR 0025):
 *   addLineSeries / addHistogramSeries + setData + remove
 *   addPane 없음 → 단일 pane 위에 overlay (priceScaleId 활용)
 *
 * 에러 처리:
 *   - REST 실패 시 해당 지표만 오류 표시 (페이지 전체 중단 X)
 *   - 캔들 부족(400) → "캔들이 부족합니다" 안내
 *
 * ADR 0023: LLM import 0건
 */
import { useEffect, useRef, useCallback } from 'react'
import type { IChartApi, ISeriesApi, SeriesType } from 'lightweight-charts'
import type { CandleInterval } from '../../api/symbol'
import { useIndicators } from '../../api/hooks/useIndicators'
import { INDICATOR_CATALOG_MAP } from '../../api/indicator'

// ──────────────────────────────────────────────
// 지표 컬러 팔레트 (최대 5종 × 복합 key)
// ──────────────────────────────────────────────

const INDICATOR_COLORS: string[] = [
  '#3b82f6', // blue-500
  '#f59e0b', // amber-500
  '#10b981', // emerald-500
  '#f43f5e', // rose-500
  '#a78bfa', // violet-400
]

const SUB_COLORS: string[] = [
  '#93c5fd', // blue-300
  '#fcd34d', // amber-300
  '#6ee7b7', // emerald-300
  '#fda4af', // rose-300
  '#c4b5fd', // violet-300
]

function formatRightPrice(value: number): string {
  return value.toLocaleString('ko-KR')
}

const RIGHT_PRICE_FORMAT = {
  type: 'custom' as const,
  minMove: 1,
  formatter: formatRightPrice,
}

// ──────────────────────────────────────────────
// 단일 지표 오버레이 훅
// ──────────────────────name──────────────────────

interface SingleOverlayProps {
  chart: IChartApi
  code: string
  indicatorId: string
  interval: CandleInterval
  colorIndex: number
  onError: (id: string, msg: string | null) => void
}

/**
 * 단일 지표를 LWC 차트에 overlay하는 내부 컴포넌트.
 * mount 시 series 추가, unmount 시 series 제거.
 */
function SingleIndicatorOverlay({
  chart,
  code,
  indicatorId,
  interval,
  colorIndex,
  onError,
}: SingleOverlayProps) {
  const catalogItem = INDICATOR_CATALOG_MAP.get(indicatorId)
  const seriesRefs = useRef<ISeriesApi<SeriesType>[]>([])

  // default params → query params 문자열 변환
  const paramsStr = catalogItem
    ? Object.entries(catalogItem.defaultParams)
        .map(([k, v]) => `${k}=${v}`)
        .join(',')
    : undefined

  const { data, isError, errorMessage } = useIndicators({
    code,
    name: indicatorId,
    interval,
    params: paramsStr || undefined,
    enabled: Boolean(chart) && Boolean(code) && Boolean(indicatorId),
  })

  // 에러 콜백
  useEffect(() => {
    if (isError) {
      onError(indicatorId, errorMessage ?? '지표 로드 실패')
    } else {
      onError(indicatorId, null)
    }
  }, [isError, errorMessage, indicatorId, onError])

  // 시리즈 생성 및 데이터 설정
  useEffect(() => {
    if (!data || data.series.length === 0) return

    const primaryColor = INDICATOR_COLORS[colorIndex % INDICATOR_COLORS.length]
    const subColor = SUB_COLORS[colorIndex % SUB_COLORS.length]

    const newSeries: ISeriesApi<SeriesType>[] = []

    try {
      if (indicatorId === 'macd') {
        // MACD: macd(line) + signal(line) + histogram(bar)
        const macdSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          priceScaleId: `indicator-${indicatorId}`,
          title: 'MACD',
        })
        const signalSeries = chart.addLineSeries({
          color: subColor,
          lineWidth: 1,
          priceScaleId: `indicator-${indicatorId}`,
          title: 'Signal',
        })
        const histSeries = chart.addHistogramSeries({
          color: primaryColor + '88',
          priceScaleId: `indicator-${indicatorId}`,
          title: 'Hist',
        })

        // 가격 스케일 독립 (캔들 스케일과 분리)
        macdSeries.priceScale().applyOptions({
          scaleMargins: { top: 0.7, bottom: 0.0 },
        })

        const macdData = data.series
          .filter((p) => p.values['macd'] !== undefined)
          .map((p) => ({
            time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
            value: parseFloat(p.values['macd']),
          }))
        const signalData = data.series
          .filter((p) => p.values['signal'] !== undefined)
          .map((p) => ({
            time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
            value: parseFloat(p.values['signal']),
          }))
        const histData = data.series
          .filter((p) => p.values['histogram'] !== undefined)
          .map((p) => {
            const v = parseFloat(p.values['histogram'])
            return {
              time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
              value: v,
              color: v >= 0 ? '#26a69a88' : '#ef535088',
            }
          })

        macdSeries.setData(macdData)
        signalSeries.setData(signalData)
        histSeries.setData(histData)
        newSeries.push(macdSeries, signalSeries, histSeries)

      } else if (indicatorId === 'bb') {
        // BB: upper + middle + lower (3 line series)
        const upperSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          lineStyle: 1, // dashed
          priceScaleId: 'right',
          priceFormat: RIGHT_PRICE_FORMAT,
          title: 'BB Upper',
        })
        const middleSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          priceScaleId: 'right',
          priceFormat: RIGHT_PRICE_FORMAT,
          title: 'BB Middle',
        })
        const lowerSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          lineStyle: 1, // dashed
          priceScaleId: 'right',
          priceFormat: RIGHT_PRICE_FORMAT,
          title: 'BB Lower',
        })

        const toLineData = (key: string) =>
          data.series
            .filter((p) => p.values[key] !== undefined)
            .map((p) => ({
              time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
              value: parseFloat(p.values[key]),
            }))

        upperSeries.setData(toLineData('upper'))
        middleSeries.setData(toLineData('middle'))
        lowerSeries.setData(toLineData('lower'))
        newSeries.push(upperSeries, middleSeries, lowerSeries)

      } else if (indicatorId === 'supertrend') {
        // Supertrend: value만 라인으로 표시 (direction은 색상으로 표현)
        const stSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 2,
          priceScaleId: 'right',
          priceFormat: RIGHT_PRICE_FORMAT,
          title: 'SuperTrend',
        })
        const stData = data.series
          .filter((p) => p.values['value'] !== undefined)
          .map((p) => {
            const direction = parseFloat(p.values['direction'] ?? '1')
            return {
              time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
              value: parseFloat(p.values['value']),
              color: direction >= 0 ? '#26a69a' : '#ef5350',
            }
          })
        stSeries.setData(stData)
        newSeries.push(stSeries)

      } else if (indicatorId === 'stochastic_d') {
        // Stochastic D: k + d (2 line series, 0-100 scale)
        const kSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          priceScaleId: `indicator-${indicatorId}`,
          title: '%K',
        })
        const dSeries = chart.addLineSeries({
          color: subColor,
          lineWidth: 1,
          priceScaleId: `indicator-${indicatorId}`,
          title: '%D',
        })
        kSeries.priceScale().applyOptions({
          scaleMargins: { top: 0.7, bottom: 0.0 },
        })

        const toLineData = (key: string) =>
          data.series
            .filter((p) => p.values[key] !== undefined)
            .map((p) => ({
              time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
              value: parseFloat(p.values[key]),
            }))

        kSeries.setData(toLineData('k'))
        dSeries.setData(toLineData('d'))
        newSeries.push(kSeries, dSeries)

      } else if (indicatorId === 'pivot') {
        // Pivot: p + r1,r2,r3 + s1,s2,s3 (7 line series, 가격 스케일)
        const pivotKeys = ['p', 'r1', 'r2', 'r3', 's1', 's2', 's3']
        const pivotColors = [
          '#fbbf24', '#f87171', '#fca5a5', '#fecaca',
          '#6ee7b7', '#34d399', '#10b981',
        ]
        for (let i = 0; i < pivotKeys.length; i++) {
          const key = pivotKeys[i]
          const pivotLineSeries = chart.addLineSeries({
            color: pivotColors[i],
            lineWidth: 1,
            lineStyle: 1, // dashed
            priceScaleId: 'right',
            priceFormat: RIGHT_PRICE_FORMAT,
            title: key.toUpperCase(),
          })
          const pivotData = data.series
            .filter((p) => p.values[key] !== undefined)
            .map((p) => ({
              time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
              value: parseFloat(p.values[key]),
            }))
          pivotLineSeries.setData(pivotData)
          newSeries.push(pivotLineSeries)
        }

      } else {
        // Single-output 지표 (기본 처리)
        const catalogInfo = INDICATOR_CATALOG_MAP.get(indicatorId)
        const outputKey = catalogInfo?.outputKeys[0] ?? indicatorId

        // RSI 등 0-100 범위 지표는 별도 priceScaleId 사용
        const needsSeparateScale = ['rsi', 'stochastic_k', 'stochrsi', 'cci', 'williams_r',
          'roc', 'ppo', 'apo', 'cmo', 'ao', 'trix', 'tsi', 'rvi', 'dpo', 'kst',
          'coppock', 'schaff', 'fisher', 'mfi', 'cmf', 'cho', 'eom', 'kvo',
          'bb_percent', 'natr', 'chop', 'mass_index', 'augen', 'adx', 'adxr',
          'aroon_up', 'aroon_down', 'vortex_plus', 'vortex_minus', 'ultosc', 'bop',
        ].includes(indicatorId)

        const priceScaleId = needsSeparateScale ? `indicator-${indicatorId}` : 'right'

        const lineSeries = chart.addLineSeries({
          color: primaryColor,
          lineWidth: 1,
          priceScaleId,
          ...(needsSeparateScale ? {} : { priceFormat: RIGHT_PRICE_FORMAT }),
          title: catalogInfo?.name.split('(')[0] ?? indicatorId,
        })

        if (needsSeparateScale) {
          lineSeries.priceScale().applyOptions({
            scaleMargins: { top: 0.7, bottom: 0.0 },
          })
        }

        const lineData = data.series
          .filter((p) => p.values[outputKey] !== undefined)
          .map((p) => ({
            time: Math.floor(new Date(p.time).getTime() / 1000) as import('lightweight-charts').UTCTimestamp,
            value: parseFloat(p.values[outputKey]),
          }))

        lineSeries.setData(lineData)
        newSeries.push(lineSeries)
      }

      seriesRefs.current = newSeries
    } catch {
      // LWC series 추가 실패 시 graceful 무시
    }

    return () => {
      // cleanup: 이 effect에서 만든 시리즈 모두 제거
      for (const s of newSeries) {
        try {
          chart.removeSeries(s)
        } catch {
          // 이미 chart가 destroy된 경우 무시
        }
      }
      seriesRefs.current = []
    }
  }, [chart, data, indicatorId, colorIndex]) // eslint-disable-line react-hooks/exhaustive-deps

  return null // DOM 렌더링 없음 — LWC API 직접 호출
}

// ──────────────────────────────────────────────
// 메인 IndicatorOverlay 컴포넌트
// ──────────────────────────────────────────────

export interface IndicatorOverlayProps {
  chart: IChartApi | null
  code: string
  interval: CandleInterval
  selectedIds: Set<string>
  /** 지표별 오류 메시지 Map (오류 없으면 null) */
  onErrorsChange?: (errors: Map<string, string>) => void
}

export function IndicatorOverlay({
  chart,
  code,
  interval,
  selectedIds,
  onErrorsChange,
}: IndicatorOverlayProps) {
  const errorsRef = useRef<Map<string, string>>(new Map())

  const handleError = useCallback(
    (id: string, msg: string | null) => {
      const prev = errorsRef.current
      if (msg === null) {
        if (!prev.has(id)) return
        const next = new Map(prev)
        next.delete(id)
        errorsRef.current = next
      } else {
        if (prev.get(id) === msg) return
        const next = new Map(prev)
        next.set(id, msg)
        errorsRef.current = next
      }
      onErrorsChange?.(new Map(errorsRef.current))
    },
    [onErrorsChange],
  )

  if (!chart) return null

  const ids = [...selectedIds]

  return (
    <>
      {ids.map((id, idx) => (
        <SingleIndicatorOverlay
          key={id}
          chart={chart}
          code={code}
          indicatorId={id}
          interval={interval}
          colorIndex={idx}
          onError={handleError}
        />
      ))}
    </>
  )
}
