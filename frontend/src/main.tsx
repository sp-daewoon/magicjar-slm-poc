import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import { App } from './App'
import { ToastProvider } from './components/ui/ToastProvider'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // REST 실패 시 토스트 + 해당 위젯만 오류 표시 (에러 핸들링 정책)
      retry: 1,
      staleTime: 30_000,
    },
  },
})

const container = document.getElementById('root')
if (!container) {
  throw new Error('root 엘리먼트를 찾을 수 없습니다.')
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <ToastProvider>
          <App />
        </ToastProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
