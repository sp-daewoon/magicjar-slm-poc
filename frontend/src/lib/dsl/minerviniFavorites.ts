import type { BlockNode, CandleField } from './blockTree'
import type { SmartMoneyStrategyPreset } from './smartMoneyPresets'

const floatLiteral = (value: number): BlockNode => ({
  kind: 'literal',
  valueType: 'float',
  value: String(value),
})

const candle = (field: CandleField): BlockNode => ({
  kind: 'candle',
  field,
})

const indicator = (
  id: string,
  period?: number,
): BlockNode => ({
  kind: 'indicator',
  id,
  params: period === undefined ? {} : { period },
})

const compare = (
  left: BlockNode,
  op: '>' | '<' | '>=' | '<=' | '==',
  right: BlockNode,
): BlockNode => ({
  kind: 'comparison',
  op,
  left,
  right,
})

const multiply = (left: BlockNode, right: BlockNode): BlockNode => ({
  kind: 'arithmetic',
  op: '*',
  left,
  right,
})

const and = (left: BlockNode, right: BlockNode): BlockNode => ({
  kind: 'logical',
  op: 'AND',
  left,
  right,
})

const andAll = (nodes: BlockNode[]): BlockNode =>
  nodes.reduce((acc, node) => (acc ? and(acc, node) : node)) as BlockNode

const close = candle('close')
const volume = candle('volume')
const ma = (period: number) => indicator('ma', period)
const natr = (period: number) => indicator('natr', period)
const volumeMa = (period: number) => indicator('volume_ma', period)
const donchianUpper = (period: number) => indicator('donchian_upper', period)
const donchianLower = (period: number) => indicator('donchian_lower', period)

export const MINERVINI_STRATEGY_FAVORITES: SmartMoneyStrategyPreset[] = [
  {
    id: 'minervini-trend-template',
    label: '미너비니 추세 템플릿',
    description: '50·150·200 이동평균 정배열과 가격 위치로 강한 상승 추세를 거르는 후보',
    suggestedInterval: '1d',
    tree: {
      entry: andAll([
        compare(close, '>', ma(50)),
        compare(ma(50), '>', ma(150)),
        compare(ma(150), '>', ma(200)),
        compare(close, '>', ma(200)),
      ]),
      exit: compare(close, '<', ma(50)),
    },
  },
  {
    id: 'minervini-near-52w-high',
    label: '미너비니 신고가 근접',
    description: '252봉 고점의 85% 이상에서 50·150선 추세가 유지되는 후보',
    suggestedInterval: '1d',
    tree: {
      entry: andAll([
        compare(close, '>=', multiply(donchianUpper(252), floatLiteral(0.85))),
        compare(close, '>', ma(50)),
        compare(ma(50), '>', ma(150)),
      ]),
      exit: compare(close, '<', ma(50)),
    },
  },
  {
    id: 'minervini-vcp-contraction',
    label: '미너비니 VCP 수축',
    description: '상승 추세 안에서 변동성과 거래량이 함께 줄어드는 VCP 후보',
    suggestedInterval: '1d',
    tree: {
      entry: andAll([
        compare(close, '>', ma(50)),
        compare(ma(20), '>', ma(50)),
        compare(natr(14), '<', natr(50)),
        compare(volume, '<', volumeMa(20)),
      ]),
      exit: compare(close, '<', ma(20)),
    },
  },
  {
    id: 'minervini-breakout-volume',
    label: '미너비니 돌파 거래량',
    description: '20봉 고점 돌파와 평균 대비 1.5배 거래량을 함께 보는 진입 후보',
    suggestedInterval: '1d',
    tree: {
      entry: andAll([
        compare(close, '>=', donchianUpper(20)),
        compare(volume, '>', multiply(volumeMa(20), floatLiteral(1.5))),
        compare(close, '>', ma(50)),
        compare(ma(20), '>', ma(50)),
      ]),
      exit: compare(close, '<', donchianLower(10)),
    },
  },
]
