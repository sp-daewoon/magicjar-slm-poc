/**
 * 카탈로그 ID 집합 — 파서 화이트리스트용
 *
 * INDICATOR_CATALOG_IDS: 80지표 id + volume_ma alias
 * PATTERN_CATALOG_IDS:   57패턴 id
 *
 * parser.ts에서 import — 순환 의존 방지를 위해 별도 파일
 * 룰 5: LLM provider import 0건
 */

/** 80지표 ID 집합 (indicator.ts INDICATOR_CATALOG.id LOCK) */
export const INDICATOR_CATALOG_IDS: ReadonlySet<string> = new Set([
  // TREND
  'ma', 'ema', 'wma', 'hma', 'dema', 'tema', 'trima', 'lwma', 't3', 'zlema',
  'kama', 'alma', 'frama', 'vidya', 'macd', 'ichimoku_tenkan', 'ichimoku_kijun',
  'supertrend', 'sar', 'regression_slope',
  // MOMENTUM
  'rsi', 'stochastic_k', 'stochastic_d', 'stochrsi', 'cci', 'williams_r', 'roc',
  'momentum', 'apo', 'ppo', 'cmo', 'ao', 'trix', 'tsi', 'rvi', 'dpo', 'kst',
  'coppock', 'schaff', 'fisher',
  // VOLUME
  'obv', 'volume_ma', 'vwap', 'mfi', 'cmf', 'cho', 'ad', 'adl', 'force',
  'vwma', 'eom', 'kvo',
  // VOLATILITY
  'bb', 'bb_width', 'bb_percent', 'atr', 'natr', 'keltner_upper', 'keltner_lower',
  'donchian_upper', 'donchian_lower', 'chop', 'mass_index', 'augen',
  // OSCILLATOR
  'adx', 'adxr', 'aroon_up', 'aroon_down', 'vortex_plus', 'vortex_minus',
  'ultosc', 'bop',
  // OTHER
  'std', 'variance', 'disparity', 'midpoint', 'midprice', 'logr', 'pivot',
  'regression_intercept', 'fvg', 'order_block',
])

/** 57패턴 ID 집합 (patterns.ts PATTERN_CATALOG.id LOCK) */
export const PATTERN_CATALOG_IDS: ReadonlySet<string> = new Set([
  // Single (14)
  'doji', 'dragonfly_doji', 'gravestone_doji', 'long_legged_doji', 'hammer',
  'hanging_man', 'inverted_hammer', 'shooting_star', 'marubozu', 'closing_marubozu',
  'spinning_top', 'belt_hold', 'high_wave', 'rickshaw_man',
  // Double (16)
  'engulfing', 'harami', 'harami_cross', 'piercing', 'dark_cloud_cover',
  'counterattack', 'on_neck', 'in_neck', 'thrusting', 'separating_lines',
  'kicking', 'kicking_by_length', 'matching_low', 'gap_side_by_side_white',
  'homing_pigeon', 'doji_star',
  // Triple+ (27)
  'morning_star', 'morning_doji_star', 'evening_star', 'evening_doji_star',
  'three_white_soldiers', 'three_black_crows', 'three_inside', 'three_outside',
  'abandoned_baby', 'three_stars_in_south', 'advance_block', 'stalled_pattern',
  'tasuki_gap', 'upside_gap_two_crows', 'three_line_strike', 'unique_three_river',
  'breakaway', 'mat_hold', 'ladder_bottom', 'concealing_baby_swallow',
  'stick_sandwich', 'tristar', 'identical_three_crows', 'two_crows',
  'up_down_gap_three_methods', 'downside_tasuki_gap', 'upside_tasuki_gap',
])
