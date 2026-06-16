/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/__tests__/setup.ts'],
    // qa Visual (Playwright) 디렉토리는 vitest 수집 대상에서 제외 — @playwright/test API 충돌 회피
    exclude: ['node_modules', 'dist', 'tests/e2e/**', 'playwright-report/**', 'test-results/**'],
  },
  server: {
    port: 5173,
    proxy: {
      // dev 환경에서 /api 요청을 api-app(8080)으로 프록시
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // STOMP WebSocket 프록시 (PR-6+에서 활성화)
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 5173,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        // 청크 분리 — LWC + 라우터는 별도 청크로 분리해 초기 로드 최소화
        manualChunks: {
          'vendor-react': ['react', 'react-dom'],
          'vendor-router': ['react-router-dom'],
          'vendor-query': ['@tanstack/react-query'],
          'vendor-chart': ['lightweight-charts'],
        },
      },
    },
  },
})
