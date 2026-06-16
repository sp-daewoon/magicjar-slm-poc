import type { BlockNode, BlockTree, CandleField } from './blockTree'

export interface SmartMoneyStrategyPreset {
  id: string
  label: string
  description: string
  suggestedInterval: string
  tree: BlockTree
}

const intLiteral = (value: number): BlockNode => ({
  kind: 'literal',
  valueType: 'integer',
  value: String(value),
})

const candle = (field: CandleField): BlockNode => ({
  kind: 'candle',
  field,
})

const fvg = (outputKey: string): BlockNode => ({
  kind: 'indicator',
  id: 'fvg',
  params: { mitigationPct: 50 },
  outputKey,
})

const orderBlock = (outputKey: string): BlockNode => ({
  kind: 'indicator',
  id: 'order_block',
  params: { swingLookback: 20, searchLookback: 20, mitigationPct: 50 },
  outputKey,
})

/**
 * 장악형(Engulfing) Order Block 헬퍼.
 *
 * order_block 신규 파라미터 detection/range를 위치 기반 직렬화 계약대로 노출한다.
 * params 객체 key 삽입 순서 = 직렬화 위치 (serializer.ts Object.values 의존):
 *   swingLookback → searchLookback → mitigationPct → detection → range
 * 결과: order_block(20, 20, 100, engulfing, body)
 *
 * mitigationPct=100: 유튜버 정의(종가 완전 관통 시 소멸)에 근사 —
 * 50% 침투 소멸을 비활성화한다.
 */
const orderBlockEngulfing = (outputKey: string): BlockNode => ({
  kind: 'indicator',
  id: 'order_block',
  params: {
    swingLookback: 20,
    searchLookback: 20,
    mitigationPct: 100,
    detection: 'engulfing',
    range: 'body',
  },
  outputKey,
})

const compare = (left: BlockNode, op: '>' | '<' | '==', right: BlockNode): BlockNode => ({
  kind: 'comparison',
  op,
  left,
  right,
})

const and = (left: BlockNode, right: BlockNode): BlockNode => ({
  kind: 'logical',
  op: 'AND',
  left,
  right,
})

export function clonePresetTree(tree: BlockTree): BlockTree {
  return JSON.parse(JSON.stringify(tree)) as BlockTree
}

export const SMART_MONEY_STRATEGY_PRESETS: SmartMoneyStrategyPreset[] = [
  {
    id: 'fvg-bullish-reclaim',
    label: 'FVG 매수 후보',
    description: '상승 FVG가 살아 있고 종가가 중간값 위로 회복한 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(fvg('bullish_active'), '==', intLiteral(1)),
        compare(candle('close'), '>', fvg('bullish_mid')),
      ),
      exit: compare(candle('close'), '<', fvg('bullish_bottom')),
    },
  },
  {
    id: 'order-block-bullish-retest',
    label: 'OB 매수 후보',
    description: '상승 BOS 이후 오더블록이 살아 있고 종가가 OB 중간값 위인 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(orderBlock('bullish_active'), '==', intLiteral(1)),
        compare(candle('close'), '>', orderBlock('bullish_mid')),
      ),
      exit: compare(candle('close'), '<', orderBlock('bullish_bottom')),
    },
  },
  {
    id: 'fvg-bearish-reject',
    label: 'FVG 매도 후보',
    description: '하락 FVG가 살아 있고 종가가 중간값 아래로 밀린 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(fvg('bearish_active'), '==', intLiteral(1)),
        compare(candle('close'), '<', fvg('bearish_mid')),
      ),
      exit: compare(candle('close'), '>', fvg('bearish_top')),
    },
  },
  {
    id: 'order-block-bearish-retest',
    label: 'OB 매도 후보',
    description: '하락 BOS 이후 오더블록이 살아 있고 종가가 OB 중간값 아래인 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(orderBlock('bearish_active'), '==', intLiteral(1)),
        compare(candle('close'), '<', orderBlock('bearish_mid')),
      ),
      exit: compare(candle('close'), '>', orderBlock('bearish_top')),
    },
  },
  {
    id: 'order-block-engulfing-bullish-retest',
    label: 'OB 장악형 매수 후보',
    description: '상승 장악형 발생 후 직전 음봉 몸통 OB가 살아 있고 종가가 OB 중간값 위인 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(orderBlockEngulfing('bullish_active'), '==', intLiteral(1)),
        compare(candle('close'), '>', orderBlockEngulfing('bullish_mid')),
      ),
      exit: compare(candle('close'), '<', orderBlockEngulfing('bullish_bottom')),
    },
  },
  {
    id: 'order-block-engulfing-bearish-retest',
    label: 'OB 장악형 매도 후보',
    description: '하락 장악형 발생 후 직전 양봉 몸통 OB가 살아 있고 종가가 OB 중간값 아래인 후보',
    suggestedInterval: '3m',
    tree: {
      entry: and(
        compare(orderBlockEngulfing('bearish_active'), '==', intLiteral(1)),
        compare(candle('close'), '<', orderBlockEngulfing('bearish_mid')),
      ),
      exit: compare(candle('close'), '>', orderBlockEngulfing('bearish_top')),
    },
  },
]
