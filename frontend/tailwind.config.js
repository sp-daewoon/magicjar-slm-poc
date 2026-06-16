/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      // magicJar 브랜드 컬러 (PR-6+에서 확장)
      colors: {
        brand: {
          50:  '#eff6ff',
          100: '#dbeafe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8',
          900: '#1e3a8a',
        },
        // 상승(매수) / 하락(매도) 컬러 — 한국 증권 표준
        up:   '#ef4444',   // 빨강 = 상승
        down: '#3b82f6',   // 파랑 = 하락
      },
      fontFamily: {
        // 한국어 UI 기본 폰트 스택 (PR-6+에서 웹폰트 추가 예정)
        sans: [
          'Pretendard',
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'system-ui',
          'sans-serif',
        ],
        mono: [
          '"JetBrains Mono"',
          'Menlo',
          'Monaco',
          '"Courier New"',
          'monospace',
        ],
      },
    },
  },
  plugins: [],
}
