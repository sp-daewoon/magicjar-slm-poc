/**
 * PresetButtons — MA periods 4 프리셋 선택 버튼 (T-MARKET R2 §2-1 SettingsModal)
 *
 * 프리셋 카탈로그 (useTrendPreference.TREND_PRESETS):
 *   classic: [5, 20, 60]   — 전통 표준
 *   mid:     [10, 30, 90]  — 중기 (주봉 1.5x)
 *   long:    [20, 60, 120] — 장기 (월봉 위주)
 *   custom:  [직접 입력]    — 사용자 정의
 */
import { TREND_PRESETS, type TrendPreset } from '../../../api/hooks/useTrendPreference'

interface Props {
  selectedId: TrendPreset['id']
  onSelect: (preset: TrendPreset | 'custom') => void
}

export function PresetButtons({ selectedId, onSelect }: Props) {
  return (
    <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="MA periods 프리셋">
      {TREND_PRESETS.map((preset) => {
        const active = selectedId === preset.id
        return (
          <button
            key={preset.id}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onSelect(preset)}
            className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 ${
              active
                ? 'border-blue-500 bg-blue-950/40 text-blue-200'
                : 'border-gray-700 text-gray-300 hover:border-gray-600 hover:bg-gray-800'
            }`}
            title={preset.description}
          >
            {preset.label}
          </button>
        )
      })}
      <button
        type="button"
        role="radio"
        aria-checked={selectedId === 'custom'}
        onClick={() => onSelect('custom')}
        className={`rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500 ${
          selectedId === 'custom'
            ? 'border-blue-500 bg-blue-950/40 text-blue-200'
            : 'border-gray-700 text-gray-300 hover:border-gray-600 hover:bg-gray-800'
        }`}
      >
        직접 입력
      </button>
    </div>
  )
}
