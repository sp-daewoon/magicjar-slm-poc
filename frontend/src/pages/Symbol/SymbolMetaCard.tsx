/**
 * SymbolMetaCard — PER·PBR·시총·EPS·BPS 메타 정보 카드
 *
 * spec §6-2 정합:
 * - KIS inquire-price source (PR-9b KisRestPriceAdapter 재사용 — PR-10b backend)
 * - null/undefined 필드 → "-" 표시 (graceful — instrument 테이블 T-IM1 미완)
 * - 시총: 억원 단위 (KIS 정합 — spec §3-2 marketCap = 억원)
 */
import type { SymbolDto } from '../../api/symbol'

interface SymbolMetaCardProps {
  symbol: Pick<SymbolDto, 'marketCap' | 'per' | 'pbr' | 'eps' | 'bps'>
}

function formatMetaNumber(value: string | undefined | null): string {
  if (!value) return '-'
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  return num.toLocaleString('ko-KR')
}

function formatMarketCap(value: string | undefined | null): string {
  if (!value) return '-'
  const num = parseFloat(value)
  if (isNaN(num)) return '-'
  // 억원 단위 포맷: 1조 이상이면 "N조 N,NNN억"
  if (num >= 10_000) {
    const jo = Math.floor(num / 10_000)
    const eok = Math.floor(num % 10_000)
    if (eok === 0) return `${jo.toLocaleString('ko-KR')}조원`
    return `${jo.toLocaleString('ko-KR')}조 ${eok.toLocaleString('ko-KR')}억원`
  }
  return `${num.toLocaleString('ko-KR')}억원`
}

interface MetaItemProps {
  label: string
  value: string
  unit?: string
}

function MetaItem({ label, value, unit }: MetaItemProps) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd className="text-sm font-semibold tabular-nums text-gray-200">
        {value}
        {unit && value !== '-' && (
          <span className="ml-0.5 text-xs font-normal text-gray-500">{unit}</span>
        )}
      </dd>
    </div>
  )
}

export function SymbolMetaCard({ symbol }: SymbolMetaCardProps) {
  return (
    <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
      <div className="text-xs font-medium text-gray-500 mb-3">기업 정보</div>
      <dl className="grid grid-cols-2 gap-x-6 gap-y-3">
        <MetaItem label="시가총액" value={formatMarketCap(symbol.marketCap)} />
        <MetaItem label="PER" value={formatMetaNumber(symbol.per)} unit="배" />
        <MetaItem label="PBR" value={formatMetaNumber(symbol.pbr)} unit="배" />
        <MetaItem label="EPS" value={formatMetaNumber(symbol.eps)} unit="원" />
        <MetaItem label="BPS" value={formatMetaNumber(symbol.bps)} unit="원" />
      </dl>
    </div>
  )
}
