/**
 * Indicator REST API 클라이언트 + DTO 정의
 *
 * spec §6-3 REST endpoint LOCK:
 *   GET /api/indicators?code=...&name=...&interval=...&params=...&from=...&to=...
 *
 * 응답 스키마 (spec §6-3 IndicatorResultDto):
 *   { spec: { name, params }, series: [{ time, values }], source }
 *
 * multi-output 매트릭스 (spec §5-2 LOCK):
 *   macd → {macd, signal, histogram}
 *   bb   → {upper, middle, lower}
 *   stochastic_d → {k, d}
 *   supertrend   → {value, direction}
 *   pivot        → {p, r1, r2, r3, s1, s2, s3}
 *
 * Single-output: values = { [indicatorName]: value }
 *
 * ADR 0023: AI import 0 — T-IND는 W2, LLM provider 금지.
 */
import apiClient from '../lib/axios'

// ──────────────────────────────────────────────
// DTO 타입 (spec §6-3 IndicatorResultDto LOCK)
// ──────────────────────────────────────────────

/** 지표 파라미터 DTO */
export interface IndicatorSpecDto {
  name: string
  params: Record<string, string>
}

/** 단일 시점 지표값 (values key는 지표별 상이) */
export interface IndicatorPointDto {
  /** ISO 8601 timestamp string */
  time: string
  /** values: 지표별 key → 값 string (BigDecimal → String 직렬화) */
  values: Record<string, string>
}

/** GET /api/indicators 응답 */
export interface IndicatorResultDto {
  spec: IndicatorSpecDto
  series: IndicatorPointDto[]
  source: string
}

// ──────────────────────────────────────────────
// 80지표 카탈로그 (indicator-catalog.md 정합)
// ──────────────────────────────────────────────

export interface IndicatorCatalogItem {
  id: string
  name: string
  category: 'TREND' | 'MOMENTUM' | 'VOLUME' | 'VOLATILITY' | 'OSCILLATOR' | 'OTHER'
  defaultParams: Record<string, string | number>
  /** multi-output 지표 여부 */
  multiOutput: boolean
  /** values map key 목록 (spec §5-2 LOCK) */
  outputKeys: string[]
}

export const INDICATOR_CATALOG: IndicatorCatalogItem[] = [
  // TREND (추세) — 20개
  { id: 'ma', name: '이동평균(MA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['ma'] },
  { id: 'ema', name: '지수이동평균(EMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['ema'] },
  { id: 'wma', name: '가중이동평균(WMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['wma'] },
  { id: 'hma', name: '헐이동평균(HMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['hma'] },
  { id: 'dema', name: '이중지수이동평균(DEMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['dema'] },
  { id: 'tema', name: '삼중지수이동평균(TEMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['tema'] },
  { id: 'trima', name: '삼각이동평균(TRIMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['trima'] },
  { id: 'lwma', name: '선형가중이동평균(LWMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['lwma'] },
  { id: 't3', name: 'T3 이동평균', category: 'TREND', defaultParams: { period: 20, volume_factor: 0.7 }, multiOutput: false, outputKeys: ['t3'] },
  { id: 'zlema', name: '지연제거지수이동평균(ZLEMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['zlema'] },
  { id: 'kama', name: '카우프만적응이동평균(KAMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['kama'] },
  { id: 'alma', name: '아르노-레그루 이동평균(ALMA)', category: 'TREND', defaultParams: { period: 20, sigma: 6.0, offset: 0.85 }, multiOutput: false, outputKeys: ['alma'] },
  { id: 'frama', name: '프랙탈적응이동평균(FRAMA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['frama'] },
  { id: 'vidya', name: '변동성 인덱스 이동평균(VIDYA)', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['vidya'] },
  { id: 'macd', name: 'MACD', category: 'TREND', defaultParams: { fast: 12, slow: 26, signal: 9 }, multiOutput: true, outputKeys: ['macd', 'signal', 'histogram'] },
  { id: 'ichimoku_tenkan', name: '이치모쿠 전환선', category: 'TREND', defaultParams: { period: 9 }, multiOutput: false, outputKeys: ['tenkan'] },
  { id: 'ichimoku_kijun', name: '이치모쿠 기준선', category: 'TREND', defaultParams: { period: 26 }, multiOutput: false, outputKeys: ['kijun'] },
  { id: 'supertrend', name: '슈퍼트렌드', category: 'TREND', defaultParams: { period: 14, multiplier: 3.0 }, multiOutput: true, outputKeys: ['value', 'direction'] },
  { id: 'sar', name: '포물선형 SAR', category: 'TREND', defaultParams: { af_start: 0.02, af_max: 0.2 }, multiOutput: false, outputKeys: ['sar'] },
  { id: 'regression_slope', name: '선형회귀 기울기', category: 'TREND', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['slope'] },

  // MOMENTUM (모멘텀) — 20개
  { id: 'rsi', name: 'RSI', category: 'MOMENTUM', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['rsi'] },
  { id: 'stochastic_k', name: '스토캐스틱 %K', category: 'MOMENTUM', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['k'] },
  { id: 'stochastic_d', name: '스토캐스틱 %D', category: 'MOMENTUM', defaultParams: { k_period: 14, d_period: 3 }, multiOutput: true, outputKeys: ['k', 'd'] },
  { id: 'stochrsi', name: '스토캐스틱 RSI', category: 'MOMENTUM', defaultParams: { rsi_period: 14, stoch_period: 14 }, multiOutput: false, outputKeys: ['stochrsi'] },
  { id: 'cci', name: '상품채널지수(CCI)', category: 'MOMENTUM', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['cci'] },
  { id: 'williams_r', name: '윌리엄스 %R', category: 'MOMENTUM', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['wr'] },
  { id: 'roc', name: '변화율(ROC)', category: 'MOMENTUM', defaultParams: { period: 10 }, multiOutput: false, outputKeys: ['roc'] },
  { id: 'momentum', name: '모멘텀', category: 'MOMENTUM', defaultParams: { period: 10 }, multiOutput: false, outputKeys: ['momentum'] },
  { id: 'apo', name: '절대가격오실레이터(APO)', category: 'MOMENTUM', defaultParams: { fast: 12, slow: 26 }, multiOutput: false, outputKeys: ['apo'] },
  { id: 'ppo', name: '퍼센트가격오실레이터(PPO)', category: 'MOMENTUM', defaultParams: { fast: 12, slow: 26 }, multiOutput: false, outputKeys: ['ppo'] },
  { id: 'cmo', name: '찬드 모멘텀 오실레이터(CMO)', category: 'MOMENTUM', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['cmo'] },
  { id: 'ao', name: '어썸 오실레이터(AO)', category: 'MOMENTUM', defaultParams: {}, multiOutput: false, outputKeys: ['ao'] },
  { id: 'trix', name: 'TRIX', category: 'MOMENTUM', defaultParams: { period: 15 }, multiOutput: false, outputKeys: ['trix'] },
  { id: 'tsi', name: '진정강도지수(TSI)', category: 'MOMENTUM', defaultParams: { long: 25, short: 13 }, multiOutput: false, outputKeys: ['tsi'] },
  { id: 'rvi', name: '상대활력지수(RVI)', category: 'MOMENTUM', defaultParams: { period: 10 }, multiOutput: false, outputKeys: ['rvi'] },
  { id: 'dpo', name: '차이가격오실레이터(DPO)', category: 'MOMENTUM', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['dpo'] },
  { id: 'kst', name: 'Know Sure Thing(KST)', category: 'MOMENTUM', defaultParams: {}, multiOutput: false, outputKeys: ['kst'] },
  { id: 'coppock', name: '코폭 커브', category: 'MOMENTUM', defaultParams: {}, multiOutput: false, outputKeys: ['coppock'] },
  { id: 'schaff', name: '샤프 추세 사이클(STC)', category: 'MOMENTUM', defaultParams: { period: 23, fast: 10, slow: 50 }, multiOutput: false, outputKeys: ['schaff'] },
  { id: 'fisher', name: '피셔 변환', category: 'MOMENTUM', defaultParams: { period: 9 }, multiOutput: false, outputKeys: ['fisher'] },

  // VOLUME (거래량) — 12개
  { id: 'obv', name: '에너지이동지표(OBV)', category: 'VOLUME', defaultParams: {}, multiOutput: false, outputKeys: ['obv'] },
  { id: 'volume_ma', name: '거래량 이동평균', category: 'VOLUME', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['volume_ma'] },
  { id: 'vwap', name: '거래량가중평균가격(VWAP)', category: 'VOLUME', defaultParams: {}, multiOutput: false, outputKeys: ['vwap'] },
  { id: 'mfi', name: '자금흐름지수(MFI)', category: 'VOLUME', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['mfi'] },
  { id: 'cmf', name: '차이킨 머니플로우(CMF)', category: 'VOLUME', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['cmf'] },
  { id: 'cho', name: '차이킨 오실레이터', category: 'VOLUME', defaultParams: { fast: 3, slow: 10 }, multiOutput: false, outputKeys: ['cho'] },
  { id: 'ad', name: 'A/D 라인', category: 'VOLUME', defaultParams: {}, multiOutput: false, outputKeys: ['ad'] },
  { id: 'adl', name: '누적/분배 라인(ADL)', category: 'VOLUME', defaultParams: {}, multiOutput: false, outputKeys: ['adl'] },
  { id: 'force', name: '힘 지수(Force Index)', category: 'VOLUME', defaultParams: { period: 13 }, multiOutput: false, outputKeys: ['force'] },
  { id: 'vwma', name: '거래량가중이동평균(VWMA)', category: 'VOLUME', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['vwma'] },
  { id: 'eom', name: '시장 이동 편의(EOM)', category: 'VOLUME', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['eom'] },
  { id: 'kvo', name: '클링거 볼륨 오실레이터', category: 'VOLUME', defaultParams: { fast: 34, slow: 55 }, multiOutput: false, outputKeys: ['kvo'] },

  // VOLATILITY (변동성) — 12개
  { id: 'bb', name: '볼린저 밴드', category: 'VOLATILITY', defaultParams: { period: 20, std_dev: 2.0 }, multiOutput: true, outputKeys: ['upper', 'middle', 'lower'] },
  { id: 'bb_width', name: '볼린저 밴드 폭', category: 'VOLATILITY', defaultParams: { period: 20, std_dev: 2.0 }, multiOutput: false, outputKeys: ['width'] },
  { id: 'bb_percent', name: '볼린저 밴드 %B', category: 'VOLATILITY', defaultParams: { period: 20, std_dev: 2.0 }, multiOutput: false, outputKeys: ['percent'] },
  { id: 'atr', name: '평균진실범위(ATR)', category: 'VOLATILITY', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['atr'] },
  { id: 'natr', name: '정규화 ATR', category: 'VOLATILITY', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['natr'] },
  { id: 'keltner_upper', name: '켈트너 채널 상단', category: 'VOLATILITY', defaultParams: { ema_period: 20, atr_period: 14, multiplier: 2.0 }, multiOutput: false, outputKeys: ['upper'] },
  { id: 'keltner_lower', name: '켈트너 채널 하단', category: 'VOLATILITY', defaultParams: { ema_period: 20, atr_period: 14, multiplier: 2.0 }, multiOutput: false, outputKeys: ['lower'] },
  { id: 'donchian_upper', name: '돈치안 채널 상단', category: 'VOLATILITY', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['upper'] },
  { id: 'donchian_lower', name: '돈치안 채널 하단', category: 'VOLATILITY', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['lower'] },
  { id: 'chop', name: '채핀 지수(Choppiness)', category: 'VOLATILITY', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['chop'] },
  { id: 'mass_index', name: '질량 지수(Mass Index)', category: 'VOLATILITY', defaultParams: { period: 25 }, multiOutput: false, outputKeys: ['mass'] },
  { id: 'augen', name: '오겐 변동성', category: 'VOLATILITY', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['augen'] },

  // OSCILLATOR (오실레이터) — 8개
  { id: 'adx', name: 'ADX', category: 'OSCILLATOR', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['adx'] },
  { id: 'adxr', name: 'ADXR', category: 'OSCILLATOR', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['adxr'] },
  { id: 'aroon_up', name: '아룬 상단', category: 'OSCILLATOR', defaultParams: { period: 25 }, multiOutput: false, outputKeys: ['up'] },
  { id: 'aroon_down', name: '아룬 하단', category: 'OSCILLATOR', defaultParams: { period: 25 }, multiOutput: false, outputKeys: ['down'] },
  { id: 'vortex_plus', name: '소용돌이 +VI', category: 'OSCILLATOR', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['plus'] },
  { id: 'vortex_minus', name: '소용돌이 -VI', category: 'OSCILLATOR', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['minus'] },
  { id: 'ultosc', name: '최종 오실레이터(UO)', category: 'OSCILLATOR', defaultParams: { p1: 7, p2: 14, p3: 28 }, multiOutput: false, outputKeys: ['uo'] },
  { id: 'bop', name: '매수-매도 균형(BOP)', category: 'OSCILLATOR', defaultParams: {}, multiOutput: false, outputKeys: ['bop'] },

  // OTHER (기타) — 8개
  { id: 'std', name: '표준편차', category: 'OTHER', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['std'] },
  { id: 'variance', name: '분산', category: 'OTHER', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['variance'] },
  { id: 'disparity', name: '이격도', category: 'OTHER', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['disparity'] },
  { id: 'midpoint', name: '중간점', category: 'OTHER', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['midpoint'] },
  { id: 'midprice', name: '중간 가격', category: 'OTHER', defaultParams: { period: 14 }, multiOutput: false, outputKeys: ['midprice'] },
  { id: 'logr', name: '로그 수익률', category: 'OTHER', defaultParams: {}, multiOutput: false, outputKeys: ['logr'] },
  { id: 'pivot', name: '피봇 포인트', category: 'OTHER', defaultParams: {}, multiOutput: true, outputKeys: ['p', 'r1', 'r2', 'r3', 's1', 's2', 's3'] },
  { id: 'regression_intercept', name: '선형회귀 절편', category: 'OTHER', defaultParams: { period: 20 }, multiOutput: false, outputKeys: ['intercept'] },
  {
    id: 'fvg',
    name: 'Fair Value Gap(FVG)',
    category: 'OTHER',
    defaultParams: { mitigationPct: 50 },
    multiOutput: true,
    outputKeys: [
      'fvg',
      'bullish_active',
      'bearish_active',
      'bullish_top',
      'bullish_bottom',
      'bullish_mid',
      'bullish_fill_pct',
      'bearish_top',
      'bearish_bottom',
      'bearish_mid',
      'bearish_fill_pct',
    ],
  },
  {
    id: 'order_block',
    name: '오더블록(Order Block)',
    category: 'OTHER',
    // detection ∈ {bos(default), engulfing}, range ∈ {wick(default), body}
    // backend IndicatorCatalog와 동일 계약 (위치 직렬화 순서 유지)
    defaultParams: {
      swingLookback: 20,
      searchLookback: 20,
      mitigationPct: 50,
      detection: 'bos',
      range: 'wick',
    },
    multiOutput: true,
    outputKeys: [
      'order_block',
      'bullish_active',
      'bearish_active',
      'bullish_top',
      'bullish_bottom',
      'bullish_mid',
      'bullish_fill_pct',
      'bearish_top',
      'bearish_bottom',
      'bearish_mid',
      'bearish_fill_pct',
      'bos_direction',
    ],
  },
]

/** id로 카탈로그 항목 빠른 조회 */
export const INDICATOR_CATALOG_MAP: Map<string, IndicatorCatalogItem> = new Map(
  INDICATOR_CATALOG.map((item) => [item.id, item]),
)

// ──────────────────────────────────────────────
// axios API 함수
// ──────────────────────────────────────────────

export interface GetIndicatorParams {
  code: string
  name: string
  interval: string
  /** key=value 쉼표 구분 문자열 (예: "period=14") */
  params?: string
  from?: string
  to?: string
  adjusted?: boolean
}

export const indicatorApi = {
  /**
   * GET /api/indicators
   * 지표 시계열 계산 결과 반환 (backend IndicatorEngine 호출 → Redis 캐시)
   *
   * @param opts - 지표 요청 파라미터
   */
  getIndicator: async (opts: GetIndicatorParams): Promise<IndicatorResultDto> => {
    const searchParams = new URLSearchParams({
      code: opts.code,
      name: opts.name,
      interval: opts.interval,
    })
    if (opts.params) searchParams.set('params', opts.params)
    if (opts.from) searchParams.set('from', opts.from)
    if (opts.to) searchParams.set('to', opts.to)
    if (opts.adjusted !== undefined) searchParams.set('adjusted', String(opts.adjusted))

    const res = await apiClient.get<IndicatorResultDto>(`/indicators?${searchParams.toString()}`)
    return res.data
  },
}
