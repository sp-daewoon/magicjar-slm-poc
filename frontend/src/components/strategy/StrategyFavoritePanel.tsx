import { useState } from 'react'
import { useModalBehavior } from '../../hooks/useModalBehavior'
import type { SmartMoneyStrategyPreset } from '../../lib/dsl/smartMoneyPresets'

interface StrategyFavoritePanelProps {
  favorites: SmartMoneyStrategyPreset[]
  hasEntry: boolean
  onApply: (preset: SmartMoneyStrategyPreset) => void
  onAddCurrent: () => void
  onRemove: (id: string) => void
}

export function StrategyFavoritePanel({
  favorites,
  hasEntry,
  onApply,
  onAddCurrent,
  onRemove,
}: StrategyFavoritePanelProps) {
  const [showGuide, setShowGuide] = useState(false)

  // ESC 닫기 + body scroll lock (가이드 모달 표시 중에만)
  useModalBehavior({ open: showGuide, onClose: () => setShowGuide(false) })

  return (
    <section className="p-2" aria-label="전략 즐겨찾기">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-gray-200">전략 즐겨찾기</h3>
        <div className="flex shrink-0 gap-1">
          <button
            type="button"
            onClick={() => setShowGuide(true)}
            className="rounded-md border border-gray-700 px-2 py-1 text-xs text-gray-300 hover:border-blue-500 hover:bg-blue-950/30 hover:text-blue-100"
          >
            가이드
          </button>
          <button
            type="button"
            onClick={onAddCurrent}
            disabled={!hasEntry}
            className="rounded-md border border-gray-700 px-2 py-1 text-xs text-gray-300 hover:border-amber-500 hover:bg-amber-950/30 hover:text-amber-100 disabled:cursor-not-allowed disabled:opacity-40"
            title={!hasEntry ? '매수 조건을 먼저 구성하세요' : undefined}
          >
            현재 추가
          </button>
        </div>
      </div>

      <div className="space-y-2">
        {favorites.map((preset) => (
          <div key={preset.id} className="flex min-w-0 gap-2">
            <button
              type="button"
              onClick={() => onApply(preset)}
              aria-label={`${preset.label} 적용`}
              className="min-w-0 flex-1 rounded-md border border-gray-700 bg-gray-950 px-3 py-2 text-left transition-colors hover:border-amber-500 hover:bg-amber-950/30 focus:outline-none focus:ring-2 focus:ring-amber-400"
              title={`${preset.description} · 추천 기준봉 ${preset.suggestedInterval}`}
            >
              <span className="block break-words text-xs font-semibold text-gray-100">
                {preset.label}
              </span>
              <span className="mt-1 block break-words text-[11px] leading-4 text-gray-500">
                {preset.description}
              </span>
              <span className="mt-1 block text-[11px] text-amber-300">
                추천 {preset.suggestedInterval}
              </span>
            </button>
            <button
              type="button"
              onClick={() => onRemove(preset.id)}
              className="self-start rounded-md border border-gray-800 px-2 py-1 text-[11px] text-gray-500 hover:border-red-700 hover:bg-red-950/30 hover:text-red-200"
              aria-label={`${preset.label} 즐겨찾기 삭제`}
            >
              삭제
            </button>
          </div>
        ))}
        {favorites.length === 0 && (
          <p className="rounded-md border border-dashed border-gray-800 px-3 py-4 text-center text-xs text-gray-500">
            즐겨찾기가 없습니다
          </p>
        )}
      </div>

      {showGuide && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
          role="dialog"
          aria-modal="true"
          aria-label="OB 매수 후보 만들기 가이드"
          onClick={(event) => {
            if (event.target === event.currentTarget) setShowGuide(false)
          }}
        >
          <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl border border-gray-700 bg-gray-900 p-5 shadow-2xl">
            <div className="mb-4 flex items-center justify-between gap-3">
              <h3 className="text-base font-semibold text-white">OB 매수 후보 직접 만들기</h3>
              <button
                type="button"
                onClick={() => setShowGuide(false)}
                className="rounded p-1 text-gray-500 hover:text-gray-300"
                aria-label="가이드 닫기"
              >
                ×
              </button>
            </div>

            <div className="space-y-4 text-sm text-gray-300">
              <section className="rounded-lg border border-gray-800 bg-gray-950 p-3">
                <h4 className="mb-2 text-xs font-semibold text-blue-200">빠른 방법: DSL로 트리 만들기</h4>
                <ol className="list-decimal space-y-1 pl-4 text-xs leading-5 text-gray-400">
                  <li>가운데에서 매수 조건 탭을 선택합니다.</li>
                  <li>아래쪽 DSL 직접 편집을 누르고 매수 수식을 붙여넣습니다.</li>
                  <li>트리로 반영을 누르면 그림 블록으로 바뀝니다.</li>
                  <li>매도 조건 탭에서도 같은 방식으로 매도 수식을 넣습니다.</li>
                  <li>왼쪽 즐겨 탭에서 현재 추가를 누르면 내 즐겨찾기로 저장됩니다.</li>
                </ol>
              </section>

              <section>
                <div className="mb-1 text-xs font-semibold text-gray-400">매수 수식</div>
                <textarea
                  readOnly
                  rows={3}
                  className="w-full resize-none rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 font-mono text-xs text-blue-200"
                  value={'(ob_bullish_active(20, 20, 50) == 1) AND (close > ob_bullish_mid(20, 20, 50))'}
                />
              </section>

              <section>
                <div className="mb-1 text-xs font-semibold text-gray-400">매도 수식</div>
                <textarea
                  readOnly
                  rows={2}
                  className="w-full resize-none rounded-lg border border-gray-700 bg-gray-950 px-3 py-2 font-mono text-xs text-orange-200"
                  value={'close < ob_bullish_bottom(20, 20, 50)'}
                />
              </section>

              <section className="rounded-lg border border-gray-800 bg-gray-950 p-3">
                <h4 className="mb-2 text-xs font-semibold text-amber-200">블록으로 직접 조립하는 순서</h4>
                <ol className="list-decimal space-y-1 pl-4 text-xs leading-5 text-gray-400">
                  <li>연산자 탭에서 AND를 누릅니다.</li>
                  <li>연산자 탭에서 비교: == 를 누르면 AND의 Left 조건에 들어갑니다.</li>
                  <li>지표 탭에서 order_block을 검색해 누르고, 출력 선택을 bullish_active로 바꿉니다.</li>
                  <li>연산자 탭에서 상수 (숫자)를 누른 뒤 값 0을 클릭해 1로 바꿉니다.</li>
                  <li>연산자 탭에서 비교: &gt; 를 누르면 AND의 Right 조건에 들어갑니다.</li>
                  <li>캔들 탭에서 종가를 누릅니다.</li>
                  <li>지표 탭에서 order_block을 다시 누르고, 출력 선택을 bullish_mid로 바꿉니다.</li>
                </ol>
              </section>

              <section className="rounded-lg border border-gray-800 bg-gray-950 p-3">
                <h4 className="mb-2 text-xs font-semibold text-emerald-200">FVG/OB 지표와 즐겨찾기 차이</h4>
                <p className="text-xs leading-5 text-gray-400">
                  지표 탭의 FVG와 OB는 재료입니다. active, mid, bottom 같은 값을 하나씩 꺼내 조건을 만듭니다.
                  즐겨찾기의 FVG/OB 후보는 그 재료들을 AND와 비교식으로 묶어둔 완성 템플릿입니다.
                </p>
              </section>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
